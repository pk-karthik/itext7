package com.itextpdf.model.layout;

import com.itextpdf.canvas.PdfCanvas;
import com.itextpdf.model.Document;
import com.itextpdf.model.elements.IElement;
import com.itextpdf.model.layout.shapes.ILayoutShape;

import java.util.List;

public class DefaultLayoutMgr implements ILayoutMgr {

    protected Document document;

    public DefaultLayoutMgr(Document document) {
        this.document = document;
    }

    @Override
    public void setCanvas(PdfCanvas canvas) {

    }

    @Override
    public void setShapes(List<ILayoutShape> shapes) {

    }

    @Override
    public List<ILayoutShape> getShapes() {
        return null;
    }

    @Override
    public IPlaceElementResult placeElement(IElement element) {
        return null;
    }

    @Override
    public IPlaceElementResult overflow(IElement element) {
        document.newPage();
        return placeElement(element);
    }
}