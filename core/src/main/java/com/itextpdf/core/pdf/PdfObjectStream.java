package com.itextpdf.core.pdf;

import com.itextpdf.basics.PdfException;
import com.itextpdf.basics.io.ByteArrayOutputStream;

import java.io.IOException;


class PdfObjectStream extends PdfStream {

    /**
     * Max number of objects in object stream.
     */
    public static final int maxObjStreamSize = 200;

    /**
     * Current object stream size (number of objects inside).
     */
    protected PdfNumber size = new PdfNumber(0);

    /**
     * Stream containing object indices, a heading part of object stream.
     */
    protected PdfOutputStream indexStream = new PdfOutputStream(new ByteArrayOutputStream());

    public PdfObjectStream(PdfDocument doc) throws PdfException {
        super(doc);
        put(PdfName.Type, PdfName.ObjStm);
        put(PdfName.N, size);
        put(PdfName.First, new PdfNumber(indexStream.getCurrentPos()));
    }

    /**
     * This constructor is for reusing ByteArrayOutputStreams of indexStream and outputStream.
     * NOTE Only for internal use in PdfWriter!
     * @param prev previous PdfObjectStream.
     */
    PdfObjectStream(PdfObjectStream prev) throws PdfException {
        this(prev.getDocument());
        ByteArrayOutputStream prevOutputStream = (ByteArrayOutputStream) prev.getOutputStream().getOutputStream();
        prevOutputStream.reset();
        initOutputStream(prevOutputStream);
        ByteArrayOutputStream prevIndexStream = ((ByteArrayOutputStream) indexStream.getOutputStream());
        prevIndexStream.reset();
        indexStream = new PdfOutputStream(prevIndexStream);
    }

    /**
     * Adds object to the object stream.
     *
     * @param object object to add.
     * @throws PdfException
     */
    public void addObject(PdfObject object) throws PdfException {
        if (size.getIntValue() == maxObjStreamSize) {
            throw new PdfException(PdfException.PdfObjectStreamReachMaxSize);
        }
        PdfOutputStream outputStream = getOutputStream();
        indexStream.writeInteger(object.getIndirectReference().getObjNr()).
                writeSpace().
                writeInteger(outputStream.getCurrentPos()).
                writeSpace();
        outputStream.write(object);
        object.getIndirectReference().setObjectStreamNumber(getIndirectReference().getObjNr());
        object.getIndirectReference().setIndex(size.getIntValue());
        outputStream.writeSpace();
        size.increment();
        ((PdfNumber)get(PdfName.First)).setValue(indexStream.getCurrentPos());
    }

    /**
     * Gets object stream size (number of objects inside).
     *
     * @return object stream size.
     */
    public int getSize() {
        return size.getIntValue();
    }

    public PdfOutputStream getIndexStream() {
        return indexStream;
    }

    @Override
    protected void releaseContent() throws PdfException {
        releaseContent(false);
    }

    private void releaseContent(boolean close) throws PdfException {
        if (close) {
            super.releaseContent();
            try {
                indexStream.close();
            } catch (IOException e) {
                throw new PdfException(PdfException.IoException, e);
            }
            indexStream = null;
        }
    }
}
