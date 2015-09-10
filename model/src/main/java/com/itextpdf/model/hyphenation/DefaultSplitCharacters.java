package com.itextpdf.model.hyphenation;

public class DefaultSplitCharacters implements ISplitCharacters {

    @Override
    public boolean isSplitCharacter(int charCode, String text, int charTextPos) {
        return (charCode <= ' ' || charCode == '-' || charCode == '\u2010'
                || (charCode >= 0x2002 && charCode <= 0x200b)
                || (charCode >= 0x2e80 && charCode < 0xd7a0)
                || (charCode >= 0xf900 && charCode < 0xfb00)
                || (charCode >= 0xfe30 && charCode < 0xfe50)
                || (charCode >= 0xff61 && charCode < 0xffa0));
    }

}