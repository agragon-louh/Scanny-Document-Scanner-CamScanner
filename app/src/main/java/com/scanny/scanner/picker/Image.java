package com.scanny.scanner.picker;

import android.net.Uri;

/**
 * A picked image. Replaces {@code com.nguyenhoanglam.imagepicker.model.Image}.
 * Images come from the system photo picker, so only a content {@link Uri} is available;
 * {@link #getPath()} returns that URI as a string (Glide and the rest of the app accept it).
 */
public class Image {
    private final Uri uri;

    public Image(Uri uri) {
        this.uri = uri;
    }

    public Uri getUri() {
        return uri;
    }

    public String getPath() {
        return uri.toString();
    }
}
