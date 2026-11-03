package com.scanny.scanner.main_utils;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.PDPage;
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission;
import com.tom_roush.pdfbox.pdmodel.encryption.InvalidPasswordException;
import com.tom_roush.pdfbox.pdmodel.encryption.PDEncryption;

import org.junit.Rule;
import org.junit.Test;
import org.junit.rules.TemporaryFolder;

import java.io.File;

public class PdfProtectorTest {
    private static final String USER_PASSWORD = "correct horse";
    private static final String OWNER_PASSWORD = "0123456789abcdef0123456789abcdef";

    @Rule
    public TemporaryFolder folder = new TemporaryFolder();

    private File protectedPdf() throws Exception {
        File plain = folder.newFile("plain.pdf");
        try (PDDocument document = new PDDocument()) {
            document.addPage(new PDPage());
            document.addPage(new PDPage());
            document.save(plain);
        }
        File target = new File(folder.getRoot(), "protected.pdf");
        PdfProtector.protect(plain, target, USER_PASSWORD, OWNER_PASSWORD);
        return target;
    }

    @Test
    public void writesAes256Revision6() throws Exception {
        try (PDDocument document = PDDocument.load(protectedPdf(), USER_PASSWORD)) {
            assertTrue(document.isEncrypted());
            PDEncryption encryption = document.getEncryption();
            assertEquals(5, encryption.getVersion());
            assertEquals(6, encryption.getRevision());
            assertEquals(256, encryption.getLength());
            assertEquals(2, document.getNumberOfPages());
        }
    }

    @Test
    public void userPasswordOnlyAllowsPrinting() throws Exception {
        try (PDDocument document = PDDocument.load(protectedPdf(), USER_PASSWORD)) {
            AccessPermission permissions = document.getCurrentAccessPermission();
            assertFalse(permissions.isOwnerPermission());
            assertTrue(permissions.canPrint());
            assertFalse(permissions.canModify());
            assertFalse(permissions.canExtractContent());
            assertFalse(permissions.canAssembleDocument());
        }
    }

    @Test
    public void ownerPasswordGrantsFullAccess() throws Exception {
        try (PDDocument document = PDDocument.load(protectedPdf(), OWNER_PASSWORD)) {
            assertTrue(document.getCurrentAccessPermission().isOwnerPermission());
        }
    }

    @Test
    public void wrongOrMissingPasswordIsRejected() throws Exception {
        File pdf = protectedPdf();
        assertThrows(InvalidPasswordException.class, () -> PDDocument.load(pdf).close());
        assertThrows(InvalidPasswordException.class, () -> PDDocument.load(pdf, "wrong password").close());
    }

    /** The app renders with iText 5 and encrypts with PDFBox; the two must interoperate. */
    @Test
    public void protectsITextOutput() throws Exception {
        File plain = folder.newFile("itext.pdf");
        com.itextpdf.text.Document document = new com.itextpdf.text.Document(com.itextpdf.text.PageSize.A4);
        com.itextpdf.text.pdf.PdfWriter.getInstance(document, new java.io.FileOutputStream(plain));
        document.open();
        document.add(new com.itextpdf.text.Paragraph("page 1"));
        document.newPage();
        document.add(new com.itextpdf.text.Paragraph("page 2"));
        document.close();

        File target = new File(folder.getRoot(), "itext-protected.pdf");
        PdfProtector.protect(plain, target, USER_PASSWORD, OWNER_PASSWORD);
        try (PDDocument loaded = PDDocument.load(target, USER_PASSWORD)) {
            assertEquals(6, loaded.getEncryption().getRevision());
            assertEquals(2, loaded.getNumberOfPages());
        }
        assertThrows(InvalidPasswordException.class, () -> PDDocument.load(target).close());
    }

    @Test
    public void passwordLengthPolicy() {
        assertFalse(PdfProtector.isStrongEnough(null));
        assertFalse(PdfProtector.isStrongEnough(""));
        assertFalse(PdfProtector.isStrongEnough("1234567"));
        assertTrue(PdfProtector.isStrongEnough("12345678"));
    }
}
