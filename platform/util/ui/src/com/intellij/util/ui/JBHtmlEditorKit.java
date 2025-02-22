// Copyright 2000-2021 JetBrains s.r.o. and contributors. Use of this source code is governed by the Apache 2.0 license that can be found in the LICENSE file.
package com.intellij.util.ui;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.util.IconLoader;
import com.intellij.openapi.util.Pair;
import com.intellij.util.ObjectUtils;
import com.intellij.util.containers.ContainerUtil;
import org.jetbrains.annotations.ApiStatus.Internal;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.imageio.ImageIO;
import javax.swing.*;
import javax.swing.event.HyperlinkEvent;
import javax.swing.event.HyperlinkListener;
import javax.swing.text.*;
import javax.swing.text.html.*;
import java.awt.*;
import java.awt.event.MouseEvent;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeEvent;
import java.beans.PropertyChangeListener;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Base64;
import java.util.Iterator;
import java.util.List;
import java.util.Objects;
import java.util.stream.StreamSupport;

public class JBHtmlEditorKit extends HTMLEditorKit {
  private static final Logger LOG = Logger.getInstance(JBHtmlEditorKit.class);

  static {
    ourCommonStyle = StartupUiUtil.createStyleSheet(
      "code { font-size: 100%; }" +  // small by Swing's default
      "small { font-size: small; }" +  // x-small by Swing's default
      "a { text-decoration: none;}" +
      // override too large default margin "ul {margin-left-ltr: 50; margin-right-rtl: 50}" from javax/swing/text/html/default.css
      "ul { margin-left-ltr: 12; margin-right-rtl: 12; }" +
      // override too large default margin "ol {margin-left-ltr: 50; margin-right-rtl: 50}" from javax/swing/text/html/default.css
      // Select ol margin to have the same indentation as "ul li" and "ol li" elements (seems value 22 suites well)
      "ol { margin-left-ltr: 22; margin-right-rtl: 22; }"
    );
    ourNoGapsBetweenParagraphsStyle = StartupUiUtil.createStyleSheet(
      "p { margin-top: 0; }"
    );
    StartupUiUtil.configureHtmlKitStylesheet();
  }

  private static final ViewFactory ourViewFactory = new JBHtmlFactory();
  private static final StyleSheet ourCommonStyle;
  private static final StyleSheet ourNoGapsBetweenParagraphsStyle;

  @Override
  public Cursor getDefaultCursor() {
    return null;
  }

  private final StyleSheet style;
  private final HyperlinkListener myHyperlinkListener;
  private final boolean myDisableLinkedCss;

  private FontResolver myFontResolver;

  public JBHtmlEditorKit() {
    this(true);
  }

  public JBHtmlEditorKit(boolean noGapsBetweenParagraphs) {
    this(noGapsBetweenParagraphs, false);
  }

  /**
   * @param disableLinkedCss Disables loading of linked CSS (from URL referenced in {@code <link>} HTML tags). JEditorPane does this loading
   *                         synchronously during {@link JEditorPane#setText(String)} operation (usually invoked in EDT).
   */
  public JBHtmlEditorKit(boolean noGapsBetweenParagraphs, boolean disableLinkedCss) {
    myDisableLinkedCss = disableLinkedCss;
    style = createStyleSheet();
    if (noGapsBetweenParagraphs) style.addStyleSheet(ourNoGapsBetweenParagraphsStyle);
    myHyperlinkListener = new HyperlinkListener() {
      @Override
      public void hyperlinkUpdate(HyperlinkEvent e) {
        Element element = e.getSourceElement();
        if (element == null) return;
        if (element.getName().equals("img")) return;

        if (e.getEventType() == HyperlinkEvent.EventType.ENTERED) {
          setUnderlined(true, element);
        }
        else if (e.getEventType() == HyperlinkEvent.EventType.EXITED) {
          setUnderlined(false, element);
        }
      }

      private void setUnderlined(boolean underlined, @NotNull Element element) {
        AttributeSet attributes = element.getAttributes();
        Object attribute = attributes.getAttribute(HTML.Tag.A);
        if (attribute instanceof MutableAttributeSet) {
          MutableAttributeSet a = (MutableAttributeSet)attribute;

          Object href = a.getAttribute(HTML.Attribute.HREF);
          Pair<Integer, Integer> aRange = findRangeOfParentTagWithGivenAttribute(element, href, HTML.Tag.A, HTML.Attribute.HREF);

          a.addAttribute(CSS.Attribute.TEXT_DECORATION, underlined ? "underline" : "none");
          ((StyledDocument)element.getDocument()).setCharacterAttributes(aRange.first, aRange.second - aRange.first, a, false);
        }
      }

      /**
       * There was a bug that if the anchor tag contained some span block, e.g.
       * {@code <a><span>Objects.</span><span>equals()</span></a>} then only one block
       * was underlined on mouse hover.
       *
       * <p>That was due to the receiver of the {@code HyperlinkEvent} was only one of child blocks
       * and we need to properly find the range occupied by the whole parent.</p>
       */
      private @NotNull Pair<Integer, Integer> findRangeOfParentTagWithGivenAttribute(
        @NotNull Element element,
        Object elementAttributeValue,
        @NotNull HTML.Tag tag,
        @NotNull HTML.Attribute attribute
      ) {
        HtmlIteratorWrapper anchorTagIterator = new HtmlIteratorWrapper(((HTMLDocument)element.getDocument()).getIterator(tag));
        return StreamSupport.stream(anchorTagIterator.spliterator(), false)
          .filter(it -> Objects.equals(it.getAttributes().getAttribute(attribute), elementAttributeValue))
          .filter(it -> it.getStartOffset() <= element.getStartOffset() && element.getStartOffset() <= it.getEndOffset())
          .map(it -> new Pair<>(it.getStartOffset(), it.getEndOffset()))
          .findFirst()
          .orElse(new Pair<>(element.getStartOffset(), element.getEndOffset()));
      }
    };
  }

  /**
   * This allows to impact resolution of fonts from CSS attributes of text
   */
  public void setFontResolver(@Nullable FontResolver fontResolver) {
    myFontResolver = fontResolver;
  }

  @Override
  public StyleSheet getStyleSheet() {
    return style;
  }

  @Override
  public Document createDefaultDocument() {
    StyleSheet styles = getStyleSheet();
    StyleSheet ss = new StyleSheetCompressionThreshold();
    ss.addStyleSheet(styles);

    HTMLDocument doc = new OurDocument(ss);
    doc.setParser(getParser());
    doc.setAsynchronousLoadPriority(4);
    doc.setTokenThreshold(100);
    return doc;
  }

  public static StyleSheet createStyleSheet() {
    StyleSheet style = new StyleSheet();
    style.addStyleSheet(ObjectUtils.notNull((StyleSheet)UIManager.getDefaults().get("StyledEditorKit.JBDefaultStyle"),
                                            StartupUiUtil.getDefaultHtmlKitCss()));
    style.addStyleSheet(ourCommonStyle);
    return style;
  }

  @Override
  public void install(final JEditorPane pane) {
    super.install(pane);
    // JEditorPane.HONOR_DISPLAY_PROPERTIES must be set after HTMLEditorKit is completely installed
    pane.addPropertyChangeListener("editorKit", new PropertyChangeListener() {
      @Override
      public void propertyChange(PropertyChangeEvent e) {
        // In case JBUI user scale factor changes, the font will be auto-updated by BasicTextUI.installUI()
        // with a font of the properly scaled size. And is then propagated to CSS, making HTML text scale dynamically.

        // The default JEditorPane's font is the label font, seems there's no need to reset it here.
        // If the default font is overridden, more so we should not reset it.
        // However, if the new font is not UIResource - it won't be auto-scaled.
        // [tav] dodo: remove the next two lines in case there're no regressions
        //Font font = getLabelFont();
        //pane.setFont(font);

        // let CSS font properties inherit from the pane's font
        pane.putClientProperty(JEditorPane.HONOR_DISPLAY_PROPERTIES, Boolean.TRUE);
        pane.removePropertyChangeListener(this);
      }
    });
    pane.addHyperlinkListener(myHyperlinkListener);

    java.util.List<LinkController> listeners1 = filterLinkControllerListeners(pane.getMouseListeners());
    java.util.List<LinkController> listeners2 = filterLinkControllerListeners(pane.getMouseMotionListeners());
    // replace just the original listener
    if (listeners1.size() == 1 && listeners1.equals(listeners2)) {
      LinkController oldLinkController = listeners1.get(0);
      pane.removeMouseListener(oldLinkController);
      pane.removeMouseMotionListener(oldLinkController);
      MouseExitSupportLinkController newLinkController = new MouseExitSupportLinkController();
      pane.addMouseListener(newLinkController);
      pane.addMouseMotionListener(newLinkController);
    }
  }

  @Override
  public ViewFactory getViewFactory() {
    return ourViewFactory;
  }

  @NotNull
  private static List<LinkController> filterLinkControllerListeners(Object @NotNull [] listeners) {
    return ContainerUtil.mapNotNull(listeners, o -> ObjectUtils.tryCast(o, LinkController.class));
  }

  @Override
  public void deinstall(@NotNull JEditorPane c) {
    c.removeHyperlinkListener(myHyperlinkListener);
    super.deinstall(c);
  }

  // This needs to be a static class to avoid memory leaks.
  // It's because StyleSheet instance gets leaked into parent (global) StyleSheet
  // due to JDK implementation nuances (see javax.swing.text.html.CSS#getStyleSheet)
  private static class StyleSheetCompressionThreshold extends StyleSheet {
    @Override
    protected int getCompressionThreshold() {
      return -1;
    }
  }

  // Workaround for https://bugs.openjdk.java.net/browse/JDK-8202529
  private static class MouseExitSupportLinkController extends HTMLEditorKit.LinkController {
    @Override
    public void mouseExited(@NotNull MouseEvent e) {
      mouseMoved(new MouseEvent(e.getComponent(), e.getID(), e.getWhen(), e.getModifiersEx(), -1, -1, e.getClickCount(), e.isPopupTrigger(),
                                e.getButton()));
    }
  }

  public static class JBHtmlFactory extends HTMLFactory {

    @Override
    public View create(Element elem) {
      AttributeSet attrs = elem.getAttributes();
      if ("img".equals(elem.getName())) {
        String src = (String)attrs.getAttribute(HTML.Attribute.SRC);
        // example: "data:image/png;base64,ENCODED_IMAGE_HERE"
        if (src != null && src.startsWith("data:image") && src.contains("base64")) {
          String[] split = src.split(",");
          if (split.length == 2) {
            try (ByteArrayInputStream bis = new ByteArrayInputStream(Base64.getDecoder().decode(split[1]))) {
              BufferedImage image = ImageIO.read(bis);
              if (image != null) {
                return new MyBufferedImageView(elem, image);
              }
            }
            catch (IllegalArgumentException | IOException e) {
              LOG.debug(e);
            }
          }
        }
      }
      else if ("icon".equals(elem.getName())) {
        Object src = attrs.getAttribute(HTML.Attribute.SRC);
        if (src instanceof String) {
          Icon icon = getIcon((String)src);
          if (icon != null) {
            return new IconView(elem, icon);
          }
        }
      }
      return super.create(elem);
    }

    @Internal
    protected @Nullable Icon getIcon(@NotNull String src) {
      return IconLoader.findIcon(src, JBHtmlEditorKit.class, true, false);
    }

    private static final class MyBufferedImageView extends View {
      private static final int DEFAULT_BORDER = 0;
      private final BufferedImage myBufferedImage;
      private final int width;
      private final int height;
      private final int border;
      private final float vAlign;

      private MyBufferedImageView(Element elem, BufferedImage myBufferedImage) {
        super(elem);
        this.myBufferedImage = myBufferedImage;
        int width = getIntAttr(HTML.Attribute.WIDTH, -1);
        int height = getIntAttr(HTML.Attribute.HEIGHT, -1);
        if (width < 0 && height < 0) {
          this.width = myBufferedImage.getWidth();
          this.height = myBufferedImage.getHeight();
        }
        else if (width < 0) {
          this.width = height * getAspectRatio();
          this.height = height;
        }
        else if (height < 0) {
          this.width = width;
          this.height = width / getAspectRatio();
        }
        else {
          this.width = width;
          this.height = height;
        }
        this.border = getIntAttr(HTML.Attribute.BORDER, DEFAULT_BORDER);
        Object alignment = elem.getAttributes().getAttribute(HTML.Attribute.ALIGN);
        float vAlign = 1.0f;
        if (alignment != null) {
          alignment = alignment.toString();
          if ("top".equals(alignment)) {
            vAlign = 0f;
          }
          else if ("middle".equals(alignment)) {
            vAlign = .5f;
          }
        }
        this.vAlign = vAlign;
      }

      private int getAspectRatio() {
        return myBufferedImage.getWidth() / myBufferedImage.getHeight();
      }

      private int getIntAttr(HTML.Attribute name, int defaultValue) {
        AttributeSet attr = getElement().getAttributes();
        if (attr.isDefined(name)) {
          String val = (String)attr.getAttribute(name);
          if (val == null) {
            return defaultValue;
          }
          else {
            try {
              return Math.max(0, Integer.parseInt(val));
            }
            catch (NumberFormatException x) {
              return defaultValue;
            }
          }
        }
        else {
          return defaultValue;
        }
      }

      @Override
      public float getPreferredSpan(int axis) {
        switch (axis) {
          case View.X_AXIS:
            return width + 2 * border;
          case View.Y_AXIS:
            return height + 2 * border;
          default:
            throw new IllegalArgumentException("Invalid axis: " + axis);
        }
      }

      @Override
      public String getToolTipText(float x, float y, Shape allocation) {
        return (String)super.getElement().getAttributes().getAttribute(HTML.Attribute.ALT);
      }

      @Override
      public void paint(Graphics g, Shape a) {
        Rectangle bounds = a.getBounds();
        g.drawImage(myBufferedImage, bounds.x + border, bounds.y + border, width, height, null);
      }

      @Override
      public Shape modelToView(int pos, Shape a, Position.Bias b) {
        int p0 = getStartOffset();
        int p1 = getEndOffset();
        if ((pos >= p0) && (pos <= p1)) {
          Rectangle r = a.getBounds();
          if (pos == p1) {
            r.x += r.width;
          }
          r.width = 0;
          return r;
        }
        return null;
      }

      @Override
      public int viewToModel(float x, float y, Shape a, Position.Bias[] bias) {
        Rectangle alloc = (Rectangle)a;
        if (x < alloc.x + alloc.width) {
          bias[0] = Position.Bias.Forward;
          return getStartOffset();
        }
        bias[0] = Position.Bias.Backward;
        return getEndOffset();
      }

      @Override
      public float getAlignment(int axis) {
        if (axis == View.Y_AXIS) {
          return vAlign;
        }
        return super.getAlignment(axis);
      }
    }

    protected static final class IconView extends View {
      private final Icon myViewIcon;

      public IconView(Element elem, Icon viewIcon) {
        super(elem);
        myViewIcon = viewIcon;
      }

      @Override
      public float getPreferredSpan(int axis) {
        switch (axis) {
          case View.X_AXIS:
            return myViewIcon.getIconWidth();
          case View.Y_AXIS:
            return myViewIcon.getIconHeight();
          default:
            throw new IllegalArgumentException("Invalid axis: " + axis);
        }
      }

      @Override
      public String getToolTipText(float x, float y, Shape allocation) {
        return (String)super.getElement().getAttributes().getAttribute(HTML.Attribute.ALT);
      }

      @Override
      public void paint(Graphics g, Shape allocation) {
        Graphics2D g2d = (Graphics2D)g;
        Composite savedComposite = g2d.getComposite();
        g2d.setComposite(AlphaComposite.SrcOver); // support transparency
        myViewIcon.paintIcon(null, g, allocation.getBounds().x, allocation.getBounds().y - 4);
        g2d.setComposite(savedComposite);
      }

      @Override
      public Shape modelToView(int pos, Shape a, Position.Bias b) throws BadLocationException {
        int p0 = getStartOffset();
        int p1 = getEndOffset();
        if ((pos >= p0) && (pos <= p1)) {
          Rectangle r = a.getBounds();
          if (pos == p1) {
            r.x += r.width;
          }
          r.width = 0;
          return r;
        }
        throw new BadLocationException(pos + " not in range " + p0 + "," + p1, pos);
      }

      @Override
      public int viewToModel(float x, float y, Shape a, Position.Bias[] bias) {
        Rectangle alloc = (Rectangle)a;
        if (x < alloc.x + (alloc.width / 2f)) {
          bias[0] = Position.Bias.Forward;
          return getStartOffset();
        }
        bias[0] = Position.Bias.Backward;
        return getEndOffset();
      }
    }
  }

  private final class OurDocument extends HTMLDocument {
    private OurDocument(StyleSheet styles) {
      super(styles);
    }

    @Override
    public Font getFont(AttributeSet a) {
      Font font = super.getFont(a);
      return myFontResolver == null ? font : myFontResolver.getFont(font, a);
    }

    @Override
    public ParserCallback getReader(int pos) {
      ParserCallback reader = super.getReader(pos);
      return myDisableLinkedCss ? new CallbackWrapper(reader) : reader;
    }

    @Override
    public ParserCallback getReader(int pos, int popDepth, int pushDepth, HTML.Tag insertTag) {
      ParserCallback reader = super.getReader(pos, popDepth, pushDepth, insertTag);
      return myDisableLinkedCss ? new CallbackWrapper(reader) : reader;
    }

    private final class CallbackWrapper extends ParserCallback {
      private final ParserCallback delegate;
      private int depth;

      private CallbackWrapper(ParserCallback delegate) { this.delegate = delegate; }

      @Override
      public void flush() throws BadLocationException {
        delegate.flush();
      }

      @Override
      public void handleText(char[] data, int pos) {
        if (depth > 0) return;
        delegate.handleText(data, pos);
      }

      @Override
      public void handleComment(char[] data, int pos) {
        if (depth > 0) return;
        delegate.handleComment(data, pos);
      }

      @Override
      public void handleStartTag(HTML.Tag t, MutableAttributeSet a, int pos) {
        if (t == HTML.Tag.LINK) depth++;
        if (depth > 0) return;
        delegate.handleStartTag(t, a, pos);
      }

      @Override
      public void handleEndTag(HTML.Tag t, int pos) {
        if (t == HTML.Tag.LINK) depth--;
        LOG.assertTrue(depth >= 0);
        if (depth > 0) return;
        delegate.handleEndTag(t, pos);
      }

      @Override
      public void handleSimpleTag(HTML.Tag t, MutableAttributeSet a, int pos) {
        if (t == HTML.Tag.LINK) return;
        delegate.handleSimpleTag(t, a, pos);
      }

      @Override
      public void handleError(String errorMsg, int pos) {
        delegate.handleError(errorMsg, pos);
      }

      @Override
      public void handleEndOfLineString(String eol) {
        delegate.handleEndOfLineString(eol);
      }
    }
  }

  /**
   * @see #setFontResolver(FontResolver)
   */
  public interface FontResolver {
    /**
     * Resolves a font for a piece of text, given its CSS attributes. {@code defaultFont} is the result of default resolution algorithm.
     */
    @NotNull Font getFont(@NotNull Font defaultFont, @NotNull AttributeSet attributeSet);
  }


  /**
   * Implements {@code java.lang.Iterable} for {@code HTMLDocument.Iterator}
   */
  public static final class HtmlIteratorWrapper implements Iterable<HTMLDocument.Iterator> {

    private final @NotNull HTMLDocument.Iterator myDelegate;

    public HtmlIteratorWrapper(@NotNull HTMLDocument.Iterator delegate) {
      myDelegate = delegate;
    }

    @NotNull
    @Override
    public Iterator<HTMLDocument.Iterator> iterator() {
      return new Iterator<>() {
        @Override
        public boolean hasNext() {
          return myDelegate.isValid();
        }

        @Override
        public HTMLDocument.Iterator next() {
          final AttributeSet attributeSet = myDelegate.getAttributes();
          final int startOffset = myDelegate.getStartOffset();
          final int endOffset = myDelegate.getEndOffset();
          final HTML.Tag tag = myDelegate.getTag();
          HTMLDocument.Iterator current = new HTMLDocument.Iterator() {
            @Override
            public AttributeSet getAttributes() {
              return attributeSet;
            }

            @Override
            public int getStartOffset() {
              return startOffset;
            }

            @Override
            public int getEndOffset() {
              return endOffset;
            }

            @Override
            public HTML.Tag getTag() {
              return tag;
            }

            @Override
            public void next() {
              throw new IllegalStateException("Must not be called");
            }

            @Override
            public boolean isValid() {
              throw new IllegalStateException("Must not be called");
            }
          };
          myDelegate.next();
          return current;
        }
      };
    }
  }
}
