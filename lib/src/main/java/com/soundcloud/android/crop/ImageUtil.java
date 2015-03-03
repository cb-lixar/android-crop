package com.soundcloud.android.crop;

import android.annotation.TargetApi;
import android.content.ContentResolver;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.graphics.BitmapRegionDecoder;
import android.graphics.Canvas;
import android.graphics.Matrix;
import android.graphics.Rect;
import android.graphics.RectF;
import android.net.Uri;

import java.io.IOException;
import java.io.InputStream;

/**
 * Various image-related utility functions.
 * Migrated from CropImageActivity because there's no need for these to be there, or even private.
 */
public class ImageUtil {
    public static Rect applyExifRotation(Rect rect, int exifRotation, int imageWidth, int imageHeight) {
        if (exifRotation != 0) {
            // Adjust crop area to account for image rotation
            Matrix matrix = new Matrix();
            matrix.setRotate(-exifRotation);

            RectF adjusted = new RectF();
            matrix.mapRect(adjusted, new RectF(rect));

            // Adjust to account for origin at 0,0
            adjusted.offset(adjusted.left < 0 ? imageWidth : 0, adjusted.top < 0 ? imageHeight : 0);
            return new Rect((int) adjusted.left, (int) adjusted.top, (int) adjusted.right, (int) adjusted.bottom);
        }
        return rect;
    }

    public static int calculateBitmapSampleSize(Context ctx, Uri bitmapUri, int maxImageSize) throws IOException {
        InputStream is = null;
        BitmapFactory.Options options = new BitmapFactory.Options();
        options.inJustDecodeBounds = true;
        try {
            is = ctx.getContentResolver().openInputStream(bitmapUri);
            BitmapFactory.decodeStream(is, null, options); // Just get image size
        } finally {
            CropUtil.closeSilently(is);
        }

        return calculateBitmapSampleSize(options.outWidth, options.outHeight, maxImageSize, maxImageSize);
    }

    public static int calculateBitmapSampleSize(int imageWidth, int imageHeight, int maxWidth, int maxHeight) {
        if (imageWidth <= maxWidth && imageHeight <= maxHeight)
            return 1;

        int ssWidth = (int)Math.ceil((double)imageWidth / (double)maxWidth);
        int ssHeight = (int)Math.ceil((double)imageHeight / (double)maxHeight);

        int sampleSize;
        if (ssWidth > ssHeight)
            sampleSize = ssWidth;
        else
            sampleSize = ssHeight;

        // Ensure it is a power of 2, erring on the side of not exceeding the maximum
        // Source: http://graphics.stanford.edu/~seander/bithacks.html#DetermineIfPowerOf2
        if (sampleSize != 0 && (sampleSize & (sampleSize - 1)) != 0)
            sampleSize++;

        return sampleSize;
    }

    /**
     * Used in earlier versions of Android where BitmapRegionDecoder is not available.
     * @throws OutOfMemoryError
     */
    public static Bitmap inMemoryCrop(RotateBitmap rotateBitmap, Rect rect, int outWidth, int outHeight)
            throws OutOfMemoryError {
        // In-memory crop means potential OOM errors,
        // but we have no choice as we can't selectively decode a bitmap with this API level
        System.gc();

        Bitmap croppedImage = Bitmap.createBitmap(outWidth, outHeight, Bitmap.Config.RGB_565);

        Canvas canvas = new Canvas(croppedImage);
        RectF dstRect = new RectF(0, 0, rect.width(), rect.height());

        Matrix m = new Matrix();
        m.setRectToRect(new RectF(rect), dstRect, Matrix.ScaleToFit.FILL);
        m.preConcat(rotateBitmap.getRotateMatrix());
        canvas.drawBitmap(rotateBitmap.getBitmap(), m, null);

        return croppedImage;
    }

    @TargetApi(10)
    public static Bitmap decodeRegionCrop(Context ctx, Uri sourceUri, int exifRotation, Rect rect,
            int outWidth, int outHeight) throws IOException, OutOfMemoryError {
        InputStream is = null;
        Bitmap croppedImage = null;
        try {
            is = ctx.getContentResolver().openInputStream(sourceUri);
            BitmapRegionDecoder decoder = BitmapRegionDecoder.newInstance(is, false);
            final int width = decoder.getWidth();
            final int height = decoder.getHeight();

            rect = applyExifRotation(rect, exifRotation, width, height);

            try {
                BitmapFactory.Options opts = new BitmapFactory.Options();
                opts.inSampleSize = calculateBitmapSampleSize(rect.width(), rect.height(), outWidth * 2, outHeight * 2);
                croppedImage = decoder.decodeRegion(rect, opts);
                if (croppedImage == null)
                    throw new IllegalArgumentException("Error cropping image.");

                boolean needPostDecodeMods = false;
                Matrix postMod = new Matrix();
                if (opts.outWidth > outWidth || opts.outHeight > outHeight) {
                    needPostDecodeMods = true;
                    postMod.postScale((float) outWidth / opts.outWidth, (float) outHeight / opts.outHeight);
                }
                if (exifRotation != 0) {
                    needPostDecodeMods = true;
                    postMod.postRotate(exifRotation);
                }
                if (needPostDecodeMods)
                    croppedImage = Bitmap.createBitmap(croppedImage, 0, 0, opts.outWidth, opts.outHeight, postMod, true);
            } catch (IllegalArgumentException e) {
                // Rethrow with some extra information
                throw new IllegalArgumentException("Rectangle " + rect + " is outside of the image ("
                        + width + "," + height + "," + exifRotation + ")", e);
            }
        } finally {
            CropUtil.closeSilently(is);
        }
        return croppedImage;
    }

}
