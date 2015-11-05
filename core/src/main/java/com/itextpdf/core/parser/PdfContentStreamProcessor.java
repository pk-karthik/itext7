package com.itextpdf.core.parser;

import com.itextpdf.basics.io.PdfTokenizer;
import com.itextpdf.basics.io.RandomAccessFileOrArray;
import com.itextpdf.basics.io.RandomAccessSourceFactory;
import com.itextpdf.core.color.*;
import com.itextpdf.core.font.PdfFont;
import com.itextpdf.core.pdf.*;

import java.io.IOException;
import java.util.*;

/**
 * Processor for a PDF content Stream.
 * @since	2.1.4
 */
public class PdfContentStreamProcessor {

    /** Listener that will be notified of render events */
    protected final EventListener eventListener;

    /**
     * The current path.
     *
     * @since 5.5.7
     */
    protected Path currentPath = new Path();

    /**
     * Default operator
     * @since 5.0.1
     */
    public static final String DEFAULTOPERATOR = "DefaultOperator";

    /** A map with all supported operators (PDF syntax). */
    final private Map<String, ContentOperator> operators;
    /** Resources for the content stream. */
    private ResourceDictionary resources;
    /** Stack keeping track of the graphics state. */
    private final Stack<GraphicsState> gsStack = new Stack<GraphicsState>();
    /** Text matrix. */
    private Matrix textMatrix;
    /** Text line matrix. */
    private Matrix textLineMatrix;
    /** A map with all supported XObject handlers */
    final private Map<PdfName, XObjectDoHandler> xobjectDoHandlers;
    /**
     * The font cache.
     * @since 5.0.6
     */
    /**  */
    final private Map<Integer, PdfFont> cachedFonts = new HashMap<Integer, PdfFont>();
    /**
     * A stack containing marked content info.
     * @since 5.0.2
     */
    private final Stack<MarkedContentInfo> markedContentStack = new Stack<MarkedContentInfo>();

    /**
     * Indicates whether the current clipping path should be modified by
     * intersecting it with the current path.
     *
     * @since 5.5.7
     */
    private boolean clip;

    /**
     * Specifies the filling rule which should be applied while calculating
     * new clipping path.
     *
     * @since 5.5.7
     */
    private int clippingRule;

    /**
     * Creates a new PDF Content Stream Processor that will send it's output to the
     * designated render listener.
     *
     * @param eventListener the {@link EventListener} that will receive rendering notifications
     */
    public PdfContentStreamProcessor(EventListener eventListener) {
        this.eventListener = eventListener;
        operators = new HashMap<String, ContentOperator>();
        populateOperators();
        xobjectDoHandlers = new HashMap<PdfName, XObjectDoHandler>();
        populateXObjectDoHandlers();
        reset();
    }

    private void populateXObjectDoHandlers(){
        registerXObjectDoHandler(PdfName.Default, new IgnoreXObjectDoHandler());
        registerXObjectDoHandler(PdfName.Form, new FormXObjectDoHandler());
        registerXObjectDoHandler(PdfName.Image, new ImageXObjectDoHandler());
    }

    /**
     * Registers a Do handler that will be called when Do for the provided XObject subtype is encountered during content processing.
     * <br>
     * If you register a handler, it is a very good idea to pass the call on to the existing registered handler (returned by this call), otherwise you
     * may inadvertently change the internal behavior of the processor.
     * @param xobjectSubType the XObject subtype this handler will process, or PdfName.DEFAULT for a catch-all handler
     * @param handler the handler that will receive notification when the Do operator for the specified subtype is encountered
     * @return the existing registered handler, if any
     * @since 5.0.1
     */
    public XObjectDoHandler registerXObjectDoHandler(PdfName xobjectSubType, XObjectDoHandler handler){
        return xobjectDoHandlers.put(xobjectSubType, handler);
    }

    /**
     * Gets the font pointed to by the indirect reference. The font may have been cached.
     * @param fontDict
     * @return the font
     * @since 5.0.6
     */
    private PdfFont getFont(PdfDictionary fontDict) throws IOException {
        Integer n = fontDict.getIndirectReference().getObjNumber();
        PdfFont font = cachedFonts.get(n);
        if (font == null) {
            /*font = PdfFont.createFont(fontDict.getDocument(), fontDict);*/ // TODO: there is a bug in reader. Delete the line below after fixing it and uncomment this line
            font = PdfFont.getDefaultFont(fontDict.getDocument());
            cachedFonts.put(n, font);
        }
        return font;
    }

    /**
     * Loads all the supported graphics and text state operators in a map.
     */
    protected void populateOperators() {
        registerContentOperator(DEFAULTOPERATOR, new IgnoreOperatorContentOperator());

        registerContentOperator("q", new PushGraphicsState());
        registerContentOperator("Q", new PopGraphicsState());
        registerContentOperator("g", new SetGrayFill());
        registerContentOperator("G", new SetGrayStroke());
        registerContentOperator("rg", new SetRGBFill());
        registerContentOperator("RG", new SetRGBStroke());
        registerContentOperator("k", new SetCMYKFill());
        registerContentOperator("K", new SetCMYKStroke());
        registerContentOperator("cs", new SetColorSpaceFill());
        registerContentOperator("CS", new SetColorSpaceStroke());
        registerContentOperator("sc", new SetColorFill());
        registerContentOperator("SC", new SetColorStroke());
        registerContentOperator("scn", new SetColorFill());
        registerContentOperator("SCN", new SetColorStroke());
        registerContentOperator("cm", new ModifyCurrentTransformationMatrix());
        registerContentOperator("gs", new ProcessGraphicsStateResource());

        SetTextCharacterSpacing tcOperator = new SetTextCharacterSpacing();
        registerContentOperator("Tc", tcOperator);
        SetTextWordSpacing twOperator = new SetTextWordSpacing();
        registerContentOperator("Tw", twOperator);
        registerContentOperator("Tz", new SetTextHorizontalScaling());
        SetTextLeading tlOperator = new SetTextLeading();
        registerContentOperator("TL", tlOperator);
        registerContentOperator("Tf", new SetTextFont());
        registerContentOperator("Tr", new SetTextRenderMode());
        registerContentOperator("Ts", new SetTextRise());

        registerContentOperator("BT", new BeginText());
        registerContentOperator("ET", new EndText());
        registerContentOperator("BMC", new BeginMarkedContent());
        registerContentOperator("BDC", new BeginMarkedContentDictionary());
        registerContentOperator("EMC", new EndMarkedContent());

        TextMoveStartNextLine tdOperator = new TextMoveStartNextLine();
        registerContentOperator("Td", tdOperator);
        registerContentOperator("TD", new TextMoveStartNextLineWithLeading(tdOperator, tlOperator));
        registerContentOperator("Tm", new TextSetTextMatrix());
        TextMoveNextLine tstarOperator = new TextMoveNextLine(tdOperator);
        registerContentOperator("T*", tstarOperator);

        ShowText tjOperator = new ShowText();
        registerContentOperator("Tj", tjOperator);
        MoveNextLineAndShowText tickOperator = new MoveNextLineAndShowText(tstarOperator, tjOperator);
        registerContentOperator("'", tickOperator);
        registerContentOperator("\"", new MoveNextLineAndShowTextWithSpacing(twOperator, tcOperator, tickOperator));
        registerContentOperator("TJ", new ShowTextArray());

        registerContentOperator("Do", new Do());

        registerContentOperator("w", new SetLineWidth());
        registerContentOperator("J", new SetLineCap());
        registerContentOperator("j", new SetLineJoin());
        registerContentOperator("M", new SetMiterLimit());
        registerContentOperator("d", new SetLineDashPattern());

        int fillStroke = PathRenderInfo.FILL | PathRenderInfo.STROKE;
        registerContentOperator("m", new MoveTo());
        registerContentOperator("l", new LineTo());
        registerContentOperator("c", new Curve());
        registerContentOperator("v", new CurveFirstPointDuplicated());
        registerContentOperator("y", new CurveFourhPointDuplicated());
        registerContentOperator("h", new CloseSubpath());
        registerContentOperator("re", new Rectangle());
        registerContentOperator("S", new PaintPath(PathRenderInfo.STROKE, -1, false));
        registerContentOperator("s", new PaintPath(PathRenderInfo.STROKE, -1, true));
        registerContentOperator("f", new PaintPath(PathRenderInfo.FILL, PdfCanvasConstants.FillingRule.NONZERO_WINDING, false));
        registerContentOperator("F", new PaintPath(PathRenderInfo.FILL, PdfCanvasConstants.FillingRule.NONZERO_WINDING, false));
        registerContentOperator("f*", new PaintPath(PathRenderInfo.FILL, PdfCanvasConstants.FillingRule.EVEN_ODD, false));
        registerContentOperator("B", new PaintPath(fillStroke, PdfCanvasConstants.FillingRule.NONZERO_WINDING, false));
        registerContentOperator("B*", new PaintPath(fillStroke, PdfCanvasConstants.FillingRule.EVEN_ODD, false));
        registerContentOperator("b", new PaintPath(fillStroke, PdfCanvasConstants.FillingRule.NONZERO_WINDING, true));
        registerContentOperator("b*", new PaintPath(fillStroke, PdfCanvasConstants.FillingRule.EVEN_ODD, true));
        registerContentOperator("n", new PaintPath(PathRenderInfo.NO_OP, -1, false));
        registerContentOperator("W", new ClipPath(PdfCanvasConstants.FillingRule.NONZERO_WINDING));
        registerContentOperator("W*", new ClipPath(PdfCanvasConstants.FillingRule.EVEN_ODD));
    }

    /**
     * Registers a content operator that will be called when the specified operator string is encountered during content processing.
     * <br>
     * If you register an operator, it is a very good idea to pass the call on to the existing registered operator (returned by this call), otherwise you
     * may inadvertently change the internal behavior of the processor.
     * @param operatorString the operator id, or DEFAULTOPERATOR for a catch-all operator
     * @param operator the operator that will receive notification when the operator is encountered
     * @return the existing registered operator, if any
     * @since 2.1.7
     */
    public ContentOperator registerContentOperator(String operatorString, ContentOperator operator){
        return operators.put(operatorString, operator);
    }

    /**
     * @return {@link java.util.Collection} containing all the registered operators strings
     * @since 5.5.6
     */
    public Collection<String> getRegisteredOperatorStrings() {
        return new ArrayList<String>(operators.keySet());
    }

    /**
     * Resets the graphics state stack, matrices and resources.
     */
    public void reset(){
        gsStack.removeAllElements();
        gsStack.add(new GraphicsState());
        textMatrix = null;
        textLineMatrix = null;
        resources = new ResourceDictionary();
        clip = false;
        currentPath = new Path();
    }

    /**
     * Returns the current graphics state.
     * @return	the graphics state
     */
    public GraphicsState gs(){
        return gsStack.peek();
    }

    /**
     * Invokes an operator.
     * @param operator	the PDF Syntax of the operator
     * @param operands	a list with operands
     */
    private void invokeOperator(PdfLiteral operator, ArrayList<PdfObject> operands) throws Exception{
        ContentOperator op = operators.get(operator.toString());
        if (op == null)
            op = operators.get(DEFAULTOPERATOR);
        op.invoke(this, operator, operands);
    }

    /**
     * Add to the marked content stack
     * @param tag the tag of the marked content
     * @param dict the PdfDictionary associated with the marked content
     * @since 5.0.2
     */
    private void beginMarkedContent(PdfName tag, PdfDictionary dict) {
        markedContentStack.push(new MarkedContentInfo(tag, dict));
    }

    /**
     * Remove the latest marked content from the stack.  Keeps track of the BMC, BDC and EMC operators.
     * @since 5.0.2
     */
    private void endMarkedContent() {
        markedContentStack.pop();
    }

    /**
     * Used to trigger beginTextBlock on the renderListener
     */
    private void beginText(){
        eventListener.eventOccured(null, EventType.BEGIN_TEXT);
    }

    /**
     * Used to trigger endTextBlock on the renderListener
     */
    private void endText(){
        eventListener.eventOccured(null, EventType.END_TEXT);
    }

    /**
     * Displays text.
     * @param string	the text to display
     */
    private void displayPdfString(PdfString string){
        TextRenderInfo renderInfo = new TextRenderInfo(string, gs(), textMatrix, markedContentStack);
        eventListener.eventOccured(renderInfo, EventType.RENDER_TEXT);
        textMatrix = new Matrix(renderInfo.getUnscaledWidth(), 0).multiply(textMatrix);
    }

    /**
     * Displays an XObject using the registered handler for this XObject's subtype
     * @param xobjectName the name of the XObject to retrieve from the resource dictionary
     */
    private void displayXObject(PdfName xobjectName) {
        PdfDictionary xobjects = (PdfDictionary) resources.get(PdfName.XObject);
        PdfStream xobjectStream = xobjects.getAsStream(xobjectName);
        PdfName subType = xobjectStream.getAsName(PdfName.Subtype);
        XObjectDoHandler handler = xobjectDoHandlers.get(subType);

        if (handler == null) {
            handler = xobjectDoHandlers.get(PdfName.Default);
        }

        handler.handleXObject(this, xobjectStream);

    }

    /**
     * Adjusts the text matrix for the specified adjustment value (see TJ operator in the PDF spec for information)
     * @param tj the text adjustment
     */
    private void applyTextAdjust(float tj){
        float adjustBy = -tj/1000f * gs().getFontSize() * gs().getHorizontalScaling();

        textMatrix = new Matrix(adjustBy, 0).multiply(textMatrix);
    }

    /**
     * Processes PDF syntax.
     * <b>Note:</b> If you re-use a given {@link PdfContentStreamProcessor}, you must call {@link PdfContentStreamProcessor#reset()}
     * @param contentBytes	the bytes of a content stream
     * @param resources		the resources that come with the content stream
     */
    public void processContent(byte[] contentBytes, PdfDictionary resources){
        this.resources.push(resources);
        try {
            PdfTokenizer tokeniser = new PdfTokenizer(new RandomAccessFileOrArray(new RandomAccessSourceFactory().createSource(contentBytes)));
            PdfContentParser ps = new PdfContentParser(tokeniser);
            ArrayList<PdfObject> operands = new ArrayList<PdfObject>();
            while (ps.parse(operands).size() > 0){
                PdfLiteral operator = (PdfLiteral)operands.get(operands.size()-1);
                if ("BI".equals(operator.toString())){
                    // we don't call invokeOperator for embedded images - this is one area of the PDF spec that is particularly nasty and inconsistent
                    PdfDictionary colorSpaceDic = resources != null ? (PdfDictionary) resources.get(PdfName.ColorSpace) : null;
                    handleInlineImage(InlineImageUtils.parseInlineImage(ps, colorSpaceDic), colorSpaceDic);
                } else {
                    invokeOperator(operator, operands);
                }
            }

        }
        catch (Exception e) {
            throw new RuntimeException(e); // TODO: was ExceptionConverter
        }
        this.resources.pop();

    }

    /**
     * Callback when an inline image is found.  This requires special handling because inline images don't follow the standard operator syntax
     * @param info the inline image
     * @param colorSpaceDic the color space for the inline immage
     */
    protected void handleInlineImage(InlineImageInfo info, PdfDictionary colorSpaceDic){
        ImageRenderInfo renderInfo = ImageRenderInfo.createForEmbeddedImage(gs().getCtm(), info, colorSpaceDic);
        eventListener.eventOccured(renderInfo, EventType.RENDER_IMAGE);
    }

    /**
     * A resource dictionary that allows stack-like behavior to support resource dictionary inheritance
     */
    private static class ResourceDictionary extends PdfDictionary{
        private final List<PdfDictionary> resourcesStack = new ArrayList<PdfDictionary>();
        public ResourceDictionary() {
        }

        public void push(PdfDictionary resources){
            resourcesStack.add(resources);
        }

        public void pop(){
            resourcesStack.remove(resourcesStack.size()-1);
        }

        @Override
        public PdfObject get(PdfName key) {
            for (int i = resourcesStack.size() - 1; i >= 0; i--){
                PdfDictionary subResource = resourcesStack.get(i);
                if (subResource != null){
                    PdfObject obj =  subResource.get(key);
                    if (obj != null) return obj;
                }
            }
            return super.get(key); // shouldn't be necessary, but just in case we've done something crazy
        }
    }

    /**
     * Processes PDF syntax.
     * <br/>
     * <strong>Note:</strong> If you re-use a given {@link 1}, you must call {@link 1#reset()}
     *
     * @param pageContentBytes The bytes of the page's content stream
     * @param pageDictionary   The page dictionary.
     */
    public void processPageContent(byte[] pageContentBytes, PdfDictionary pageDictionary) {
        initClippingPath(pageDictionary);
        GraphicsState gs = gs();
        eventListener.eventOccured(new PathRenderInfo(gs.getClippingPath(), -1, -1, gs), EventType.CLIP_PATH_CHANGED); // TODO: refactor -1 constants
        processContent(pageContentBytes, pageDictionary.getAsDictionary(PdfName.Resources));
    }

    private void initClippingPath(PdfDictionary pageDictionary) {
        PdfArray cropBoxRect = getCropboxRect(pageDictionary);
        Path clippingPath = new Path();

        float x = cropBoxRect.getAsNumber(0).getFloatValue();
        float y = cropBoxRect.getAsNumber(1).getFloatValue();
        float width = cropBoxRect.getAsNumber(2).getFloatValue() - x;
        float height = cropBoxRect.getAsNumber(3).getFloatValue() - y;

        clippingPath.rectangle(x, y, width, height);
        gs().setClippingPath(clippingPath);
    }

    private PdfArray getCropboxRect(PdfDictionary pageDictionary) {
        PdfArray cropboxRect = pageDictionary.getAsArray(PdfName.CropBox);
        return cropboxRect != null ? cropboxRect : pageDictionary.getAsArray(PdfName.MediaBox);
    }

    /**
     * Displays the current path.
     *
     * @param operation One of the possible combinations of {@link com.itextpdf.core.parser.PathRenderInfo#STROKE}
     *                  and {@link com.itextpdf.core.parser.PathRenderInfo#FILL} values or
     *                  {@link com.itextpdf.core.parser.PathRenderInfo#NO_OP}
     * @param rule      Either {@link PdfCanvasConstants.FillingRule#NONZERO_WINDING} or {@link PdfCanvasConstants.FillingRule#EVEN_ODD}
     *                  In case it isn't applicable pass any <CODE>byte</CODE> value.
     * @since 5.5.7
     */
    protected void paintPath(int operation, int rule) {
        PathRenderInfo renderInfo = new PathRenderInfo(currentPath, operation, rule, gs());
        eventListener.eventOccured(renderInfo, EventType.RENDER_PATH);

        if (clip) {
            clip = false;
            GraphicsState gs = gs();
            gs.clip(currentPath, clippingRule);
            eventListener.eventOccured(new PathRenderInfo(gs.getClippingPath(), -1, -1, gs), EventType.CLIP_PATH_CHANGED); // TODO: refactor -1 constants
        }

        currentPath = new Path();
    }

    /**
     * A content operator implementation (unregistered).
     */
    private static class IgnoreOperatorContentOperator implements ContentOperator{
        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands){
            // ignore the operator
        }
    }

    /**
     * A content operator implementation (TJ).
     */
    private static class ShowTextArray implements ContentOperator{
        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            PdfArray array = (PdfArray)operands.get(0);
            float tj = 0;
            for (Iterator<PdfObject> i = array.listIterator(); i.hasNext(); ) {
                PdfObject entryObj = i.next();
                if (entryObj instanceof PdfString){
                    processor.displayPdfString((PdfString)entryObj);
                    tj = 0;
                } else {
                    tj = ((PdfNumber)entryObj).getFloatValue();
                    processor.applyTextAdjust(tj);
                }
            }

        }
    }

    /**
     * A content operator implementation (").
     */
    private static class MoveNextLineAndShowTextWithSpacing implements ContentOperator{
        private final SetTextWordSpacing setTextWordSpacing;
        private final SetTextCharacterSpacing setTextCharacterSpacing;
        private final MoveNextLineAndShowText moveNextLineAndShowText;

        public MoveNextLineAndShowTextWithSpacing(SetTextWordSpacing setTextWordSpacing, SetTextCharacterSpacing setTextCharacterSpacing, MoveNextLineAndShowText moveNextLineAndShowText) {
            this.setTextWordSpacing = setTextWordSpacing;
            this.setTextCharacterSpacing = setTextCharacterSpacing;
            this.moveNextLineAndShowText = moveNextLineAndShowText;
        }

        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            PdfNumber aw = (PdfNumber)operands.get(0);
            PdfNumber ac = (PdfNumber)operands.get(1);
            PdfString string = (PdfString)operands.get(2);

            ArrayList<PdfObject> twOperands = new ArrayList<PdfObject>(1);
            twOperands.add(0, aw);
            setTextWordSpacing.invoke(processor, null, twOperands);

            ArrayList<PdfObject> tcOperands = new ArrayList<PdfObject>(1);
            tcOperands.add(0, ac);
            setTextCharacterSpacing.invoke(processor, null, tcOperands);

            ArrayList<PdfObject> tickOperands = new ArrayList<PdfObject>(1);
            tickOperands.add(0, string);
            moveNextLineAndShowText.invoke(processor, null, tickOperands);
        }
    }

    /**
     * A content operator implementation (').
     */
    private static class MoveNextLineAndShowText implements ContentOperator{
        private final TextMoveNextLine textMoveNextLine;
        private final ShowText showText;
        public MoveNextLineAndShowText(TextMoveNextLine textMoveNextLine, ShowText showText) {
            this.textMoveNextLine = textMoveNextLine;
            this.showText = showText;
        }

        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            textMoveNextLine.invoke(processor, null, new ArrayList<PdfObject>(0));
            showText.invoke(processor, null, operands);
        }
    }

    /**
     * A content operator implementation (Tj).
     */
    private static class ShowText implements ContentOperator{
        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            PdfString string = (PdfString)operands.get(0);

            processor.displayPdfString(string);
        }
    }


    /**
     * A content operator implementation (T*).
     */
    private static class TextMoveNextLine implements ContentOperator{
        private final TextMoveStartNextLine moveStartNextLine;
        public TextMoveNextLine(TextMoveStartNextLine moveStartNextLine){
            this.moveStartNextLine = moveStartNextLine;
        }

        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            ArrayList<PdfObject> tdoperands = new ArrayList<PdfObject>(2);
            tdoperands.add(0, new PdfNumber(0));
            tdoperands.add(1, new PdfNumber(-processor.gs().leading));
            moveStartNextLine.invoke(processor, null, tdoperands);
        }
    }

    /**
     * A content operator implementation (Tm).
     */
    private static class TextSetTextMatrix implements ContentOperator{
        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            float a = ((PdfNumber)operands.get(0)).getFloatValue();
            float b = ((PdfNumber)operands.get(1)).getFloatValue();
            float c = ((PdfNumber)operands.get(2)).getFloatValue();
            float d = ((PdfNumber)operands.get(3)).getFloatValue();
            float e = ((PdfNumber)operands.get(4)).getFloatValue();
            float f = ((PdfNumber)operands.get(5)).getFloatValue();

            processor.textLineMatrix = new Matrix(a, b, c, d, e, f);
            processor.textMatrix = processor.textLineMatrix;
        }
    }

    /**
     * A content operator implementation (TD).
     */
    private static class TextMoveStartNextLineWithLeading implements ContentOperator{
        private final TextMoveStartNextLine moveStartNextLine;
        private final SetTextLeading setTextLeading;
        public TextMoveStartNextLineWithLeading(TextMoveStartNextLine moveStartNextLine, SetTextLeading setTextLeading){
            this.moveStartNextLine = moveStartNextLine;
            this.setTextLeading = setTextLeading;
        }
        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            float ty = ((PdfNumber)operands.get(1)).getFloatValue();

            ArrayList<PdfObject> tlOperands = new ArrayList<PdfObject>(1);
            tlOperands.add(0, new PdfNumber(-ty));
            setTextLeading.invoke(processor, null, tlOperands);
            moveStartNextLine.invoke(processor, null, operands);
        }
    }

    /**
     * A content operator implementation (Td).
     */
    private static class TextMoveStartNextLine implements ContentOperator{
        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            float tx = ((PdfNumber)operands.get(0)).getFloatValue();
            float ty = ((PdfNumber)operands.get(1)).getFloatValue();

            Matrix translationMatrix = new Matrix(tx, ty);
            processor.textMatrix =  translationMatrix.multiply(processor.textLineMatrix);
            processor.textLineMatrix = processor.textMatrix;
        }
    }

    /**
     * A content operator implementation (Tf).
     */
    private static class SetTextFont implements ContentOperator{
        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            PdfName fontResourceName = (PdfName)operands.get(0);
            float size = ((PdfNumber)operands.get(1)).getFloatValue();

            PdfDictionary fontsDictionary = (PdfDictionary) processor.resources.get(PdfName.Font);
            PdfDictionary fontDict = fontsDictionary.getAsDictionary(fontResourceName);
            PdfFont font = null;
            try { // TODO: extract exception
                font = processor.getFont(fontDict);
            } catch (IOException e) {
                e.printStackTrace();
            }

            processor.gs().font = font;
            processor.gs().fontSize = size;

        }
    }

    /**
     * A content operator implementation (Tr).
     */
    private static class SetTextRenderMode implements ContentOperator{
        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            PdfNumber render = (PdfNumber)operands.get(0);
            processor.gs().renderMode = render.getIntValue();
        }
    }

    /**
     * A content operator implementation (Ts).
     */
    private static class SetTextRise implements ContentOperator{
        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            PdfNumber rise = (PdfNumber)operands.get(0);
            processor.gs().rise = rise.getFloatValue();
        }
    }

    /**
     * A content operator implementation (TL).
     */
    private static class SetTextLeading implements ContentOperator{
        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            PdfNumber leading = (PdfNumber)operands.get(0);
            processor.gs().leading = leading.getFloatValue();
        }
    }

    /**
     * A content operator implementation (Tz).
     */
    private static class SetTextHorizontalScaling implements ContentOperator{
        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            PdfNumber scale = (PdfNumber)operands.get(0);
            processor.gs().horizontalScaling = scale.getFloatValue()/100f;
        }
    }

    /**
     * A content operator implementation (Tc).
     */
    private static class SetTextCharacterSpacing implements ContentOperator{
        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            PdfNumber charSpace = (PdfNumber)operands.get(0);
            processor.gs().characterSpacing = charSpace.getFloatValue();
        }
    }

    /**
     * A content operator implementation (Tw).
     */
    private static class SetTextWordSpacing implements ContentOperator{
        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            PdfNumber wordSpace = (PdfNumber)operands.get(0);
            processor.gs().wordSpacing = wordSpace.getFloatValue();
        }
    }

    /**
     * A content operator implementation (gs).
     */
    private static class ProcessGraphicsStateResource implements ContentOperator{
        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {

            PdfName dictionaryName = (PdfName)operands.get(0);
            PdfDictionary extGState = (PdfDictionary) processor.resources.get(PdfName.ExtGState);
            if (extGState == null)
                throw new IllegalArgumentException(/*MessageLocalization.getComposedMessage(*/"resources.do.not.contain.extgstate.entry.unable.to.process.operator.1"/*, operator)*/);
            PdfDictionary gsDic = extGState.getAsDictionary(dictionaryName);
            if (gsDic == null)
                throw new IllegalArgumentException(/*MessageLocalization.getComposedMessage(*/"1.is.an.unknown.graphics.state.dictionary"/*, dictionaryName)*/);

            // at this point, all we care about is the FONT entry in the GS dictionary
            PdfArray fontParameter = gsDic.getAsArray(PdfName.Font);
            if (fontParameter != null){
                PdfFont font = null;
                try { // TODO: remove exception
                    font = processor.getFont(fontParameter.getAsDictionary(0));
                } catch (IOException e) {
                    e.printStackTrace();
                }
                float size = fontParameter.getAsNumber(1).getFloatValue();

                processor.gs().font = font;
                processor.gs().fontSize = size;
            }
        }
    }

    /**
     * A content operator implementation (q).
     */
    private static class PushGraphicsState implements ContentOperator{
        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            GraphicsState gs = processor.gsStack.peek();
            GraphicsState copy = new GraphicsState(gs);
            processor.gsStack.push(copy);
        }
    }

    /**
     * A content operator implementation (cm).
     */
    private static class ModifyCurrentTransformationMatrix implements ContentOperator{
        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            float a = ((PdfNumber)operands.get(0)).getFloatValue();
            float b = ((PdfNumber)operands.get(1)).getFloatValue();
            float c = ((PdfNumber)operands.get(2)).getFloatValue();
            float d = ((PdfNumber)operands.get(3)).getFloatValue();
            float e = ((PdfNumber)operands.get(4)).getFloatValue();
            float f = ((PdfNumber)operands.get(5)).getFloatValue();
            Matrix matrix = new Matrix(a, b, c, d, e, f);
            processor.gs().updateCtm(matrix);
        }
    }

    /**
     * Gets a color based on a list of operands.
     */
    private static Color getColor(PdfName colorSpace, List<PdfObject> operands) {
        if (PdfName.DeviceGray.equals(colorSpace)) {
            return getColor(1, operands);
        }
        if (PdfName.DeviceRGB.equals(colorSpace)) {
            return getColor(3, operands);
        }
        if (PdfName.DeviceCMYK.equals(colorSpace)) {
            return getColor(4, operands);
        }
        return null;
    }

    /**
     * Gets a color based on a list of operands.
     */
    private static Color getColor(int nOperands, List<PdfObject> operands) {
        float[] c = new float[nOperands];
        for (int i = 0; i < nOperands; i++) {
            c[i] = ((PdfNumber)operands.get(i)).getFloatValue();
        }
        switch (nOperands) {
            case 1:
                return new DeviceGray(c[0]);
            case 3:
                return new DeviceRgb(c[0], c[1], c[2]);
            case 4:
                return new DeviceCmyk(c[0], c[1], c[2], c[3]);
        }
        return null;
    }

    /**
     * A content operator implementation (Q).
     */
    protected static class PopGraphicsState implements ContentOperator{
        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            processor.gsStack.pop();
            GraphicsState gs = processor.gs();
            processor.eventListener.eventOccured(new PathRenderInfo(gs.getClippingPath(), -1, -1, gs), EventType.CLIP_PATH_CHANGED);
        }
    }

    /**
     * A content operator implementation (g).
     */
    private static class SetGrayFill implements ContentOperator{
        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            processor.gs().fillColor = getColor(1, operands);
        }
    }

    /**
     * A content operator implementation (G).
     */
    private static class SetGrayStroke implements ContentOperator{
        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            processor.gs().strokeColor = getColor(1, operands);
        }
    }

    /**
     * A content operator implementation (rg).
     */
    private static class SetRGBFill implements ContentOperator{
        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            processor.gs().fillColor = getColor(3, operands);
        }
    }

    /**
     * A content operator implementation (RG).
     */
    private static class SetRGBStroke implements ContentOperator{
        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            processor.gs().strokeColor = getColor(3, operands);
        }
    }

    /**
     * A content operator implementation (rg).
     */
    private static class SetCMYKFill implements ContentOperator{
        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            processor.gs().fillColor = getColor(4, operands);
        }
    }

    /**
     * A content operator implementation (RG).
     */
    private static class SetCMYKStroke implements ContentOperator{
        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            processor.gs().strokeColor = getColor(4, operands);
        }
    }

    /**
     * A content operator implementation (CS).
     */
    private static class SetColorSpaceFill implements ContentOperator{
        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            processor.gs().colorSpaceFill = (PdfName)operands.get(0);
        }
    }

    /**
     * A content operator implementation (cs).
     */
    private static class SetColorSpaceStroke implements ContentOperator{
        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            processor.gs().colorSpaceStroke = (PdfName)operands.get(0);
        }
    }

    /**
     * A content operator implementation (sc / scn).
     */
    private static class SetColorFill implements ContentOperator{
        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            processor.gs().fillColor = getColor(processor.gs().colorSpaceFill, operands);
        }
    }

    /**
     * A content operator implementation (SC / SCN).
     */
    private static class SetColorStroke implements ContentOperator{
        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            processor.gs().strokeColor = getColor(processor.gs().colorSpaceStroke, operands);
        }
    }

    /**
     * A content operator implementation (BT).
     */
    private static class BeginText implements ContentOperator{
        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            processor.textMatrix = new Matrix();
            processor.textLineMatrix = processor.textMatrix;
            processor.beginText();
        }
    }

    /**
     * A content operator implementation (ET).
     */
    private static class EndText implements ContentOperator{
        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            processor.textMatrix = null;
            processor.textLineMatrix = null;
            processor.endText();
        }
    }

    /**
     * A content operator implementation (BMC).
     * @since 5.0.2
     */
    private static class BeginMarkedContent implements ContentOperator{

        public void invoke(PdfContentStreamProcessor processor,
                           PdfLiteral operator, ArrayList<PdfObject> operands) {
            processor.beginMarkedContent((PdfName)operands.get(0), new PdfDictionary());
        }

    }

    /**
     * A content operator implementation (BDC).
     * @since 5.0.2
     */
    private static class BeginMarkedContentDictionary implements ContentOperator{

        public void invoke(PdfContentStreamProcessor processor,
                           PdfLiteral operator, ArrayList<PdfObject> operands) {

            PdfObject properties = operands.get(1);

            processor.beginMarkedContent((PdfName)operands.get(0), getPropertiesDictionary(properties, processor.resources));
        }

        private PdfDictionary getPropertiesDictionary(PdfObject operand1, ResourceDictionary resources){
            if (operand1.isDictionary())
                return (PdfDictionary)operand1;

            PdfName dictionaryName = ((PdfName)operand1);
            return (PdfDictionary) resources.get(dictionaryName);
        }
    }

    /**
     * A content operator implementation (EMC).
     * @since 5.0.2
     */
    private static class EndMarkedContent implements ContentOperator{
        public void invoke(PdfContentStreamProcessor processor,
                           PdfLiteral operator, ArrayList<PdfObject> operands) {
            processor.endMarkedContent();
        }
    }

    /**
     * A content operator implementation (Do).
     */
    private static class Do implements ContentOperator{
        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            PdfName xobjectName = (PdfName)operands.get(0);
            processor.displayXObject(xobjectName);
        }
    }

    /**
     * A content operator implementation (w).
     */
    private static class SetLineWidth implements ContentOperator {

        public void invoke(PdfContentStreamProcessor processor, PdfLiteral oper, ArrayList<PdfObject> operands) {
            float lineWidth = ((PdfNumber) operands.get(0)).getFloatValue();
            processor.gs().setLineWidth(lineWidth);
        }
    }

    /**
     * A content operator implementation (J).
     */
    private class SetLineCap implements ContentOperator {

        public void invoke(PdfContentStreamProcessor processor, PdfLiteral oper, ArrayList<PdfObject> operands) {
            int lineCap = ((PdfNumber) operands.get(0)).getIntValue();
            processor.gs().setLineCapStyle(lineCap);
        }
    }

    /**
     * A content operator implementation (j).
     */
    private class SetLineJoin implements ContentOperator {

        public void invoke(PdfContentStreamProcessor processor, PdfLiteral oper, ArrayList<PdfObject> operands) {
            int lineJoin = ((PdfNumber) operands.get(0)).getIntValue();
            processor.gs().setLineJoinStyle(lineJoin);
        }
    }

    /**
     * A content operator implementation (M).
     */
    private class SetMiterLimit implements ContentOperator {

        public void invoke(PdfContentStreamProcessor processor, PdfLiteral oper, ArrayList<PdfObject> operands) {
            float miterLimit = ((PdfNumber) operands.get(0)).getFloatValue();
            processor.gs().setMiterLimit(miterLimit);
        }
    }

    /**
     * A content operator implementation (d).
     */
    private class SetLineDashPattern implements ContentOperator {

        public void invoke(PdfContentStreamProcessor processor, PdfLiteral oper, ArrayList<PdfObject> operands) {
            LineDashPattern pattern = new LineDashPattern(((PdfArray) operands.get(0)),
                    ((PdfNumber) operands.get(1)).getFloatValue());
            processor.gs().setLineDashPattern(pattern);
        }
    }

    /**
     * An XObject subtype handler for FORM
     */
    private static class FormXObjectDoHandler implements XObjectDoHandler{

        public void handleXObject(PdfContentStreamProcessor processor, PdfStream stream) {

            final PdfDictionary resources = stream.getAsDictionary(PdfName.Resources);

            // we read the content bytes up here so if it fails we don't leave the graphics state stack corrupted
            // this is probably not necessary (if we fail on this, probably the entire content stream processing
            // operation should be rejected
            byte[] contentBytes;
            contentBytes = stream.getBytes();
            final PdfArray matrix = stream.getAsArray(PdfName.Matrix);

            new PushGraphicsState().invoke(processor, null, null);

            if (matrix != null){
                float a = matrix.getAsNumber(0).getFloatValue();
                float b = matrix.getAsNumber(1).getFloatValue();
                float c = matrix.getAsNumber(2).getFloatValue();
                float d = matrix.getAsNumber(3).getFloatValue();
                float e = matrix.getAsNumber(4).getFloatValue();
                float f = matrix.getAsNumber(5).getFloatValue();
                Matrix formMatrix = new Matrix(a, b, c, d, e, f);
                processor.gs().updateCtm(formMatrix);
            }

            processor.processContent(contentBytes, resources);

            new PopGraphicsState().invoke(processor, null, null);

        }

    }

    /**
     * An XObject subtype handler for IMAGE
     */
    private static class ImageXObjectDoHandler implements XObjectDoHandler{

        public void handleXObject(PdfContentStreamProcessor processor, PdfStream xobjectStream) {
            PdfDictionary colorSpaceDic = (PdfDictionary) processor.resources.get(PdfName.ColorSpace);
            ImageRenderInfo renderInfo = ImageRenderInfo.createForXObject(processor.gs().getCtm(), xobjectStream, colorSpaceDic);
            processor.eventListener.eventOccured(renderInfo, EventType.RENDER_IMAGE);
        }
    }

    /**
     * An XObject subtype handler that does nothing
     */
    private static class IgnoreXObjectDoHandler implements XObjectDoHandler{
        public void handleXObject(PdfContentStreamProcessor processor, PdfStream xobjectStream) {
            // ignore XObject subtype
        }
    }

    /**
     * A content operator implementation (m).
     * @since 5.5.6
     */
    private static class MoveTo implements ContentOperator {

        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            float x = ((PdfNumber) operands.get(0)).getFloatValue();
            float y = ((PdfNumber) operands.get(1)).getFloatValue();
            processor.currentPath.moveTo(x, y);
        }
    }

    /**
     * A content operator implementation (l).
     * @since 5.5.6
     */
    private static class LineTo implements ContentOperator {

        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            float x = ((PdfNumber) operands.get(0)).getFloatValue();
            float y = ((PdfNumber) operands.get(1)).getFloatValue();
            processor.currentPath.lineTo(x, y);
        }
    }

    /**
     * A content operator implementation (c).
     * @since 5.5.6
     */
    private static class Curve implements ContentOperator {

        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            float x1 = ((PdfNumber) operands.get(0)).getFloatValue();
            float y1 = ((PdfNumber) operands.get(1)).getFloatValue();
            float x2 = ((PdfNumber) operands.get(2)).getFloatValue();
            float y2 = ((PdfNumber) operands.get(3)).getFloatValue();
            float x3 = ((PdfNumber) operands.get(4)).getFloatValue();
            float y3 = ((PdfNumber) operands.get(5)).getFloatValue();
            processor.currentPath.curveTo(x1, y1, x2, y2, x3, y3);
        }
    }

    /**
     * A content operator implementation (v).
     * @since 5.5.6
     */
    private static class CurveFirstPointDuplicated implements ContentOperator {

        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            float x2 = ((PdfNumber) operands.get(0)).getFloatValue();
            float y2 = ((PdfNumber) operands.get(1)).getFloatValue();
            float x3 = ((PdfNumber) operands.get(2)).getFloatValue();
            float y3 = ((PdfNumber) operands.get(3)).getFloatValue();
            processor.currentPath.curveTo(x2, y2, x3, y3);
        }
    }

    /**
     * A content operator implementation (y).
     * @since 5.5.6
     */
    private static class CurveFourhPointDuplicated implements ContentOperator {

        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            float x1 = ((PdfNumber) operands.get(0)).getFloatValue();
            float y1 = ((PdfNumber) operands.get(1)).getFloatValue();
            float x3 = ((PdfNumber) operands.get(2)).getFloatValue();
            float y3 = ((PdfNumber) operands.get(3)).getFloatValue();
            processor.currentPath.curveFromTo(x1, y1, x3, y3);
        }
    }

    /**
     * A content operator implementation (h).
     * @since 5.5.6
     */
    private static class CloseSubpath implements ContentOperator {

        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            processor.currentPath.closeSubpath();
        }
    }

    /**
     * A content operator implementation (re).
     * @since 5.5.6
     */
    private static class Rectangle implements ContentOperator {

        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            float x = ((PdfNumber) operands.get(0)).getFloatValue();
            float y = ((PdfNumber) operands.get(1)).getFloatValue();
            float w = ((PdfNumber) operands.get(2)).getFloatValue();
            float h = ((PdfNumber) operands.get(3)).getFloatValue();
            processor.currentPath.rectangle(x, y, w, h);
        }
    }

    /**
     * A content operator implementation (S, s, f, F, f*, B, B*, b, b*).
     * @since 5.5.6
     */
    private static class PaintPath implements ContentOperator {

        private int operation;
        private int rule;
        private boolean close;

        /**
         * Constructs PainPath object.
         *
         * @param operation One of the possible combinations of {@link com.itextpdf.core.parser.PathRenderInfo#STROKE}
         *                  and {@link com.itextpdf.core.parser.PathRenderInfo#FILL} values or
         *                  {@link com.itextpdf.core.parser.PathRenderInfo#NO_OP}
         * @param rule      Either {@link PdfCanvasConstants.FillingRule#NONZERO_WINDING} or {@link PdfCanvasConstants.FillingRule#EVEN_ODD}
         *                  In case it isn't applicable pass any value.
         * @param close     Indicates whether the path should be closed or not.
         */
        public PaintPath(int operation, int rule, boolean close) {
            this.operation = operation;
            this.rule = rule;
            this.close = close;
        }

        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            if (close) {
                processor.currentPath.closeSubpath();
            }

            processor.paintPath(operation, rule);
        }
    }

    /**
     * A content operator implementation (W, W*)
     * @since 5.5.6
     */
    private static class ClipPath implements ContentOperator {

        private int rule;

        public ClipPath(int rule) {
            this.rule = rule;
        }

        public void invoke(PdfContentStreamProcessor processor, PdfLiteral operator, ArrayList<PdfObject> operands) {
            processor.clip = true;
            processor.clippingRule = rule;
        }
    }
}