// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
@file:Suppress("ReplaceGetOrSet", "ReplaceNegatedIsEmptyWithIsNotEmpty")

package org.jetbrains.intellij.build.tasks

import io.opentelemetry.api.common.AttributeKey
import io.opentelemetry.api.common.Attributes
import net.schmizz.keepalive.KeepAliveProvider
import net.schmizz.sshj.DefaultConfig
import net.schmizz.sshj.SSHClient
import net.schmizz.sshj.sftp.SFTPClient
import net.schmizz.sshj.transport.verification.PromiscuousVerifier
import org.apache.commons.compress.archivers.zip.Zip64Mode
import org.apache.commons.compress.archivers.zip.ZipArchiveEntryPredicate
import org.apache.commons.compress.archivers.zip.ZipArchiveOutputStream
import org.apache.commons.compress.archivers.zip.ZipFile
import org.apache.log4j.ConsoleAppender
import org.apache.log4j.Level
import org.apache.log4j.Logger
import org.apache.log4j.PatternLayout
import org.jetbrains.intellij.build.io.NioFileDestination
import org.jetbrains.intellij.build.io.NioFileSource
import org.jetbrains.intellij.build.io.runAsync
import org.jetbrains.intellij.build.io.writeNewFile
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.security.SecureRandom
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.Consumer
import java.util.zip.Deflater

private val random by lazy { SecureRandom() }

fun main() {
  @Suppress("SpellCheckingInspection")
  signMac(
    host = "localhost",
    user = System.getProperty("ssh.user"),
    password = System.getProperty("ssh.password"),
    codesignString = "",
    remoteDirPrefix = "test",
    signScript = Path.of("/Volumes/data/Documents/idea/community/platform/build-scripts/tools/mac/scripts/signbin.sh"),
    files = listOf(Path.of("/Applications/Idea.app/Contents/bin/fsnotifier2")),
    artifactDir = Path.of("/tmp/signed-files"),
    artifactBuilt = {},
  )
}

// our zip for JARs, but here we need to support file permissions - that's why apache compress is used
fun prepareMacZip(macZip: Path,
                  sitFile: Path,
                  productJson: ByteArray,
                  macAdditionalDir: Path?,
                  zipRoot: String) {
  Files.newByteChannel(macZip, StandardOpenOption.READ).use { sourceFileChannel ->
    ZipFile(sourceFileChannel).use { zipFile ->
      writeNewFile(sitFile) { targetFileChannel ->
        ZipArchiveOutputStream(targetFileChannel).use { out ->
          // file is used only for transfer to mac builder
          out.setLevel(Deflater.BEST_SPEED)
          out.setUseZip64(Zip64Mode.Never)

          // exclude existing product-info.json as a custom one will be added
          val productJsonZipPath = "$zipRoot/Resources/product-info.json"
          zipFile.copyRawEntries(out, ZipArchiveEntryPredicate { it.name != productJsonZipPath })
          if (macAdditionalDir != null) {
            out.dir(macAdditionalDir, prefix = "$zipRoot/")
          }

          out.entry(productJsonZipPath, productJson)
        }
      }
    }
  }
}

// 0644 octal -> 420 decimal
private const val regularFileMode = 420
// 0777 octal -> 511 decimal
private const val executableFileMode = 511

@Suppress("unused")
fun signMacApp(
  host: String,
  user: String,
  password: String,
  codesignString: String,
  fullBuildNumber: String,
  notarize: Boolean,
  bundleIdentifier: String,
  appArchiveFile: Path,
  jreArchiveFile: Path?,
  communityHome: Path,
  artifactDir: Path,
  dmgImage: Path?,
  artifactBuilt: Consumer<Path>,
  publishAppArchive: Boolean,
) {
  executeTask(host, user, password, "intellij-builds/${fullBuildNumber}") { ssh, sftp, remoteDir ->
    tracer.spanBuilder("upload file")
      .setAttribute("file", appArchiveFile.toString())
      .setAttribute("remoteDir", remoteDir)
      .setAttribute("host", host)
      .startSpan()
      .use {
        sftp.put(NioFileSource(appArchiveFile, filePermission = regularFileMode), "$remoteDir/${appArchiveFile.fileName}")
      }

    if (jreArchiveFile != null) {
      tracer.spanBuilder("upload JRE archive")
        .setAttribute("file", jreArchiveFile.toString())
        .setAttribute("remoteDir", remoteDir)
        .setAttribute("host", host)
        .startSpan()
        .use {
          sftp.put(NioFileSource(jreArchiveFile, filePermission = regularFileMode), "$remoteDir/${jreArchiveFile.fileName}")
        }
    }

    val scriptDir = communityHome.resolve("platform/build-scripts/tools/mac/scripts")
    tracer.spanBuilder("upload scripts")
      .setAttribute("scriptDir", scriptDir.toString())
      .setAttribute("remoteDir", remoteDir)
      .setAttribute("host", host)
      .startSpan().use {
        sftp.put(NioFileSource(scriptDir.resolve("entitlements.xml"), filePermission = regularFileMode), "$remoteDir/entitlements.xml")
        @Suppress("SpellCheckingInspection")
        for (fileName in listOf("sign.sh", "notarize.sh", "signapp.sh", "makedmg.sh", "makedmg.pl")) {
          sftp.put(NioFileSource(scriptDir.resolve(fileName), filePermission = executableFileMode), "$remoteDir/$fileName")
        }

        if (dmgImage != null) {
          sftp.put(NioFileSource(dmgImage, filePermission = regularFileMode), "$remoteDir/$fullBuildNumber.png")
        }
      }

    val args = listOf(
      appArchiveFile.fileName.toString(),
      fullBuildNumber,
      user,
      password,
      codesignString,
      jreArchiveFile?.fileName?.toString() ?: "no-jdk",
      if (notarize) "yes" else "no",
      bundleIdentifier,
      publishAppArchive.toString(),
    )

    val env = System.getenv("ARTIFACTORY_URL")?.takeIf { it.isNotEmpty() }?.let { "ARTIFACTORY_URL=$it " } ?: ""
    @Suppress("SpellCheckingInspection")
    tracer.spanBuilder("sign mac app").setAttribute("file", appArchiveFile.toString()).startSpan().useWithScope {
      signFile(remoteDir = remoteDir,
               commandString = "$env'$remoteDir/signapp.sh' '${args.joinToString("' '")}'",
               file = appArchiveFile,
               ssh = ssh,
               ftpClient = sftp,
               artifactDir = artifactDir,
               artifactBuilt = artifactBuilt)
      if (publishAppArchive) {
        downloadResult(remoteFile = "$remoteDir/${appArchiveFile.fileName}",
                       localFile = appArchiveFile,
                       ftpClient = sftp,
                       failedToSign = null)
      }
    }

    if (publishAppArchive) {
      artifactBuilt.accept(appArchiveFile)
    }

    if (dmgImage != null) {
      val fileNameWithoutExt = appArchiveFile.fileName.toString().removeSuffix(".sit")
      val dmgFile = artifactDir.resolve("$fileNameWithoutExt.dmg")
      tracer.spanBuilder("build dmg").setAttribute("file", dmgFile.toString()).startSpan().useWithScope {
        @Suppress("SpellCheckingInspection")
        processFile(localFile = dmgFile,
                    ssh = ssh,
                    commandString = "'$remoteDir/makedmg.sh' '${fileNameWithoutExt}' '$fullBuildNumber'",
                    artifactDir = artifactDir,
                    artifactBuilt = artifactBuilt,
                    taskLogClassifier = "dmg")
        downloadResult(remoteFile = "$remoteDir/${dmgFile.fileName}",
                       localFile = dmgFile,
                       ftpClient = sftp,
                       failedToSign = null)

        artifactBuilt.accept(dmgFile)
      }
    }
  }
}

fun signMac(
  host: String,
  user: String,
  password: String,
  codesignString: String,
  remoteDirPrefix: String,
  signScript: Path,
  files: List<Path>,
  artifactDir: Path,
  artifactBuilt: Consumer<Path>
) {
  val failedToSign = mutableListOf<Path>()
  executeTask(host, user, password, remoteDirPrefix) { ssh, sftp, remoteDir ->
    val remoteSignScript = "$remoteDir/${signScript.fileName}"
    sftp.put(NioFileSource(signScript, executableFileMode), remoteSignScript)

    for (file in files) {
      tracer.spanBuilder("sign").setAttribute("file", file.toString()).startSpan().useWithScope {
        signFile(remoteDir = remoteDir,
                 commandString = "'$remoteSignScript' '${file.fileName}' '$user' '$password' '$codesignString'",
                 file = file,
                 ssh = ssh,
                 ftpClient = sftp,
                 artifactDir = artifactDir,
                 artifactBuilt = artifactBuilt)
        downloadResult(remoteFile = "$remoteDir/${file.fileName}", localFile = file, ftpClient = sftp, failedToSign = failedToSign)
      }
    }
  }

  if (!failedToSign.isEmpty()) {
    throw RuntimeException("Failed to sign files: ${failedToSign.joinToString { it.toString() }}")
  }
}

private fun signFile(remoteDir: String,
                        file: Path,
                        ssh: SSHClient,
                        ftpClient: SFTPClient,
                        commandString: String,
                        artifactDir: Path,
                        artifactBuilt: Consumer<Path>) {
  ftpClient.put(NioFileSource(file), "$remoteDir/${file.fileName}")
  processFile(localFile = file,
              ssh = ssh,
              commandString = commandString,
              artifactDir = artifactDir,
              artifactBuilt = artifactBuilt,
              taskLogClassifier = "sign")
}

private fun processFile(localFile: Path,
                        ssh: SSHClient,
                        commandString: String,
                        artifactDir: Path,
                        artifactBuilt: Consumer<Path>,
                        taskLogClassifier: String) {
  val fileName = localFile.fileName.toString()

  val logFile = artifactDir.resolve("macos-logs").resolve("$taskLogClassifier-$fileName.log")
  Files.createDirectories(logFile.parent)
  ssh.startSession().use { session ->
    val command = session.exec(commandString)
    try {
      // use CompletableFuture because get will call ForkJoinPool.helpAsyncBlocker, so, other tasks in FJP will be executed while waiting
      CompletableFuture.allOf(
        runAsync { command.inputStream.transferTo(System.out) },
        runAsync { Files.copy(command.errorStream, logFile, StandardCopyOption.REPLACE_EXISTING) }
      ).get(3, TimeUnit.HOURS)

      command.join(1, TimeUnit.MINUTES)
    }
    catch (e: Exception) {
      val logFileLocation = if (Files.exists(logFile)) artifactDir.relativize(logFile) else "<internal error - log file is not created>"
      throw RuntimeException("SSH command failed, details are available in $logFileLocation: ${e.message}", e)
    }
    finally {
      if (Files.exists(logFile)) {
        artifactBuilt.accept(logFile)
      }
      command.close()
    }

    if (command.exitStatus != 0) {
      throw RuntimeException("SSH command failed, details are available in ${artifactDir.relativize(logFile)}" +
                             " (exitStatus=${command.exitStatus}, exitErrorMessage=${command.exitErrorMessage})")
    }
  }
}

private fun downloadResult(remoteFile: String,
                           localFile: Path,
                           ftpClient: SFTPClient,
                           failedToSign: MutableList<Path>?) {
  tracer.spanBuilder("download file")
    .setAttribute("remoteFile", remoteFile)
    .setAttribute("localFile", localFile.toString())
    .startSpan()
    .use { span ->
      val localFileParent = localFile.parent
      val tempFile = localFileParent.resolve("${localFile.fileName}.download")
      Files.createDirectories(localFileParent)
      var attempt = 1
      do {
        try {
          ftpClient.get(remoteFile, NioFileDestination(tempFile))
        }
        catch (e: Exception) {
          span.addEvent("cannot download $remoteFile", Attributes.of(
            AttributeKey.longKey("attemptNumber"), attempt.toLong(),
            AttributeKey.stringKey("error"), e.toString(),
            AttributeKey.stringKey("remoteFile"), remoteFile,
          ))
          attempt++
          if (attempt > 3) {
            Files.deleteIfExists(tempFile)
            if (failedToSign == null) {
              throw RuntimeException("Failed to sign file: $localFile")
            }
            else {
              failedToSign.add(localFile)
            }
            return
          }
          else {
            continue
          }
        }

        break
      }
      while (true)

      if (attempt != 1) {
        span.addEvent("file was downloaded", Attributes.of(
          AttributeKey.longKey("attemptNumber"), attempt.toLong(),
        ))
      }
      Files.move(tempFile, localFile, StandardCopyOption.REPLACE_EXISTING)
    }
}

private val initLog by lazy {
  System.setProperty("log4j.defaultInitOverride", "true")
  val root = Logger.getRootLogger()
  if (!root.allAppenders.hasMoreElements()) {
    root.level = Level.INFO
    root.addAppender(ConsoleAppender(PatternLayout(PatternLayout.DEFAULT_CONVERSION_PATTERN)))
  }
}

private fun generateRemoteDirName(remoteDirPrefix: String): String {
  val currentDateTimeString = LocalDateTime.now().format(DateTimeFormatter.ISO_LOCAL_DATE_TIME).replace(':', '-')
  return "$remoteDirPrefix-$currentDateTimeString-${java.lang.Long.toUnsignedString(random.nextLong(), Character.MAX_RADIX)}"
}

private inline fun executeTask(host: String,
                               user: String,
                               password: String,
                               remoteDirPrefix: String,
                               task: (ssh: SSHClient, sftp: SFTPClient, remoteDir: String) -> Unit) {
  initLog
  val config = DefaultConfig()
  config.keepAliveProvider = KeepAliveProvider.KEEP_ALIVE

  SSHClient(config).use { ssh ->
    ssh.addHostKeyVerifier(PromiscuousVerifier())
    ssh.connect(host)
    ssh.authPassword(user, password)

    ssh.newSFTPClient().use { sftp ->
      val remoteDir = generateRemoteDirName(remoteDirPrefix)
      sftp.mkdir(remoteDir)
      try {
        task(ssh, sftp, remoteDir)
      }
      finally {
        // as odd as it is, session can only be used once
        // https://stackoverflow.com/a/23467751
        removeDir(ssh, remoteDir)
      }
    }
  }
}

private fun removeDir(ssh: SSHClient, remoteDir: String) {
  tracer.spanBuilder("remove remote dir").setAttribute("remoteDir", remoteDir).startSpan().use {
    ssh.startSession().use { session ->
      val command = session.exec("rm -rf '$remoteDir'")
      command.join(30, TimeUnit.SECONDS)
      // must be called before checking exit code
      command.close()
      if (command.exitStatus != 0) {
        throw RuntimeException("cannot remove remote directory (exitStatus=${command.exitStatus}, " +
                               "exitErrorMessage=${command.exitErrorMessage})")
      }
    }
  }
}