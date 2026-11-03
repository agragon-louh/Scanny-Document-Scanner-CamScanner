package com.scanny.scanner.main_utils;

import com.tom_roush.pdfbox.io.MemoryUsageSetting;
import com.tom_roush.pdfbox.pdmodel.PDDocument;
import com.tom_roush.pdfbox.pdmodel.encryption.AccessPermission;
import com.tom_roush.pdfbox.pdmodel.encryption.StandardProtectionPolicy;

import java.io.File;
import java.io.IOException;

/**
 * Password-protects PDFs with AES-256, security handler revision 6 (ISO 32000-2).
 *
 * <p>iText 5 can only write revision 5, a deprecated Adobe extension that checks the password
 * with a single SHA-256 hash and is therefore very cheap to brute-force offline. PDFBox writes
 * revision 6, which uses the iterated hash from ISO 32000-2.</p>
 */
public final class PdfProtector {
    /** Shortest password accepted for a protected PDF. */
    public static final int MIN_PASSWORD_LENGTH = 8;

    private PdfProtector() {
    }

    public static boolean isStrongEnough(String password) {
        return password != null && password.length() >= MIN_PASSWORD_LENGTH;
    }

    /**
     * Writes an encrypted copy of {@code source} to {@code target}. Opening it needs
     * {@code userPassword}; the copy can be printed but not edited, copied or reassembled
     * without {@code ownerPassword}.
     */
    public static void protect(File source, File target, String userPassword, String ownerPassword) throws IOException {
        if (userPassword == null || userPassword.isEmpty()) {
            throw new IllegalArgumentException("Empty password");
        }
        try (PDDocument document = PDDocument.load(source, MemoryUsageSetting.setupTempFileOnly())) {
            AccessPermission permissions = new AccessPermission();
            permissions.setCanModify(false);
            permissions.setCanAssembleDocument(false);
            permissions.setCanExtractContent(false);
            permissions.setCanFillInForm(false);
            permissions.setCanModifyAnnotations(false);
            permissions.setCanPrint(true);
            permissions.setCanPrintFaithful(true);
            StandardProtectionPolicy policy = new StandardProtectionPolicy(ownerPassword, userPassword, permissions);
            // 256-bit AES: PDFBox uses security handler revision 6 for this key length.
            policy.setEncryptionKeyLength(256);
            document.protect(policy);
            document.save(target);
        }
    }
}
