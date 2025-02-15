package cgeo.geocaching;

import cgeo.geocaching.activity.AbstractActionBarActivity;
import cgeo.geocaching.databinding.ImageeditActivityBinding;
import cgeo.geocaching.models.Image;
import cgeo.geocaching.settings.Settings;
import cgeo.geocaching.ui.ImageActivityHelper;
import cgeo.geocaching.ui.TextSpinner;
import cgeo.geocaching.ui.dialog.Dialogs;
import cgeo.geocaching.utils.ImageUtils;
import cgeo.geocaching.utils.functions.Func1;

import android.content.Intent;
import android.graphics.Bitmap;
import android.net.Uri;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;

import java.util.Arrays;

import org.apache.commons.lang3.ArrayUtils;
import org.apache.commons.lang3.tuple.ImmutablePair;
import org.apache.commons.lang3.tuple.ImmutableTriple;

public class ImageEditActivity extends AbstractActionBarActivity {
    private ImageeditActivityBinding binding;

    private final TextSpinner<Integer> imageScale = new TextSpinner<>();

    private static final int RC_EDIT_IMAGE_EXTERNAL = 50;

    private static final int ANIMATION_DURATION_IN_MS = 200;

    private static final String SAVED_STATE_IMAGE = "cgeo.geocaching.saved_state_image";
    private static final String SAVED_STATE_ORIGINAL_IMAGE_URI = "cgeo.geocaching.saved_state_original_image_uri";
    private static final String SAVED_STATE_IMAGE_INDEX = "cgeo.geocaching.saved_state_image_index";
    private static final String SAVED_STATE_IMAGE_SCALE = "cgeo.geocaching.saved_state_image_scale";
    private static final String SAVED_STATE_MAX_IMAGE_UPLOAD_SIZE = "cgeo.geocaching.saved_state_max_image_upload_size";
    private static final String SAVED_STATE_GEOCODE = "cgeo.geocaching.saved_state_geocode";

    private Uri originalImageUri;
    private Image image;
    private int imageIndex = -1;
    private long maxImageUploadSize;

    @Nullable private String geocode;

    @Override
    public void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setThemeAndContentView(R.layout.imageedit_activity);
        binding = ImageeditActivityBinding.bind(findViewById(R.id.imageselect_activity_viewroot));

        imageScale.setSpinner(findViewById(R.id.logImageScale))
                .setValues(Arrays.asList(ArrayUtils.toObject(getResources().getIntArray(R.array.log_image_scale_values))))
                .setChangeListener(Settings::setLogImageScale);

        setTitle(getString(R.string.log_edit_image));

        // Get parameters from intent and basic cache information from database
        final Bundle extras = getIntent().getExtras();
        if (extras != null) {
            image = extras.getParcelable(Intents.EXTRA_IMAGE);
            originalImageUri = image == null ? null : image.uri;
            imageIndex = extras.getInt(Intents.EXTRA_INDEX, -1);
            maxImageUploadSize = extras.getLong(Intents.EXTRA_MAX_IMAGE_UPLOAD_SIZE);
            geocode = extras.getString(Intents.EXTRA_GEOCODE);

        }

        // Restore previous state
        if (savedInstanceState != null) {
            image = savedInstanceState.getParcelable(SAVED_STATE_IMAGE);
            originalImageUri = savedInstanceState.getParcelable(SAVED_STATE_ORIGINAL_IMAGE_URI);
            imageIndex = savedInstanceState.getInt(SAVED_STATE_IMAGE_INDEX, -1);
            imageScale.set(savedInstanceState.getInt(SAVED_STATE_IMAGE_SCALE));
            maxImageUploadSize = savedInstanceState.getLong(SAVED_STATE_MAX_IMAGE_UPLOAD_SIZE);
            geocode = savedInstanceState.getString(SAVED_STATE_GEOCODE);
        }

        if (image == null) {
            image = Image.NONE;
            imageScale.set(Settings.getLogImageScale());
        } else {
            imageScale.set(image.targetScale);
        }
        updateScaleValueDisplay();

        binding.imageRotate.setOnClickListener(view -> rotateBy(90));
        binding.imageFlip.setOnClickListener(view -> flipHorizontal());
        binding.imageEditExternal.setOnClickListener(view -> editExternal());

        if (image.hasTitle()) {
            binding.caption.setText(image.getTitle());
            Dialogs.moveCursorToEnd(binding.caption);
        }

        if (image.hasDescription()) {
            binding.description.setText(image.getDescription());
            Dialogs.moveCursorToEnd(binding.caption);
        }

        loadImagePreview();
    }

    @Override
    public boolean onCreateOptionsMenu(final Menu menu) {
        super.onCreateOptionsMenu(menu);
        getMenuInflater().inflate(R.menu.menu_ok_cancel, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(final MenuItem item) {
        final int itemId = item.getItemId();
        if (itemId == R.id.menu_item_cancel) {
            finishEdit(false);
        } else if (itemId == R.id.menu_item_save) {
            finishEdit(true);
        } else {
            return super.onOptionsItemSelected(item);
        }
        return true;

    }

    @Override
    protected void onSaveInstanceState(@NonNull final Bundle outState) {
        super.onSaveInstanceState(outState);
        syncEditTexts();
        outState.putParcelable(SAVED_STATE_IMAGE, image);
        outState.putParcelable(SAVED_STATE_ORIGINAL_IMAGE_URI, originalImageUri);
        outState.putInt(SAVED_STATE_IMAGE_INDEX, imageIndex);
        outState.putInt(SAVED_STATE_IMAGE_SCALE, imageScale.get());
        outState.putLong(SAVED_STATE_MAX_IMAGE_UPLOAD_SIZE, maxImageUploadSize);
        outState.putString(SAVED_STATE_GEOCODE, geocode);
    }

    public void finishEdit(final boolean saveInfo) {
        if (saveInfo) {

            final Intent intent = new Intent();
            syncEditTexts();
            intent.putExtra(Intents.EXTRA_IMAGE, image);
            intent.putExtra(Intents.EXTRA_INDEX, imageIndex);
            //"originalImageUri" is now obsolete. But we never delete originalImage (in case log gets not stored)
            setResult(RESULT_OK, intent);
            finish();
        } else {
            deleteImageFromDeviceIfNotOriginal();
            setResult(RESULT_CANCELED);
            finish();
        }
    }

    private void syncEditTexts() {
        image = new Image.Builder()
                .setUrl(image.uri)
                .setTitle(binding.caption.getText().toString())
                .setTargetScale(imageScale.get())
                .setDescription(binding.description.getText().toString())
                .build();
    }

    private void setImageTo(final Uri newUri) {
        deleteImageFromDeviceIfNotOriginal();
        final Image copyImage = ImageUtils.toLocalLogImage(geocode, newUri);
        image = (image == null ? Image.NONE : image).buildUpon().setUrl(copyImage.uri).build();
    }

    private void ensureImageEditCopy() {
        if (image == null || image.getUri() == null || !imageUriIsOriginal()) {
            return;
        }
        final Image copyImage = ImageUtils.toLocalLogImage(geocode, image.uri);
        image = (image == null ? Image.NONE : image).buildUpon().setUrl(copyImage.uri).build();
    }

    private boolean deleteImageFromDeviceIfNotOriginal() {
        if (!imageUriIsOriginal()) {
            return ImageUtils.deleteImage(image.getUri());
        }
        return false;
    }

    private boolean imageUriIsOriginal() {
        if (originalImageUri == null) {
            return image == null || image.getUri() == null;
        }
        return image != null && originalImageUri.equals(image.getUri());
    }

    @Override
    protected void onActivityResult(final int requestCode, final int resultCode, final Intent data) {
        super.onActivityResult(requestCode, resultCode, data);  // call super to make lint happy
        if (requestCode == RC_EDIT_IMAGE_EXTERNAL) {
            loadImagePreview();
        }
    }

    private void loadImagePreview() {
        ImageActivityHelper.displayImageAsync(image, binding.imagePreview, iv -> {
            iv.setRotationY(0);
            iv.setRotation(0);
        });
        updateScaleValueDisplay();

    }

    private void enableImageEditActions(final boolean enable) {
        binding.imageEditExternal.setEnabled(enable);
        binding.imageRotate.setEnabled(enable);
        binding.imageFlip.setEnabled(enable);
    }

    private void updateScaleValueDisplay() {
        int width = -1;
        int height = -1;
        if (image != null) {
            final ImmutablePair<Integer, Integer> size = ImageUtils.getImageSize(image.getUri());
            if (size != null) {
                width = size.left;
                height = size.right;
            }
        }
        updateScaleValueDisplayIntern(width, height);
    }

    private void updateScaleValueDisplayIntern(final int width, final int height) {
        imageScale.setDisplayMapper(scaleSize -> {
            if (width < 0 || height < 0) {
                return scaleSize < 0 ? getResources().getString(R.string.log_image_scale_option_noscaling) :
                        getResources().getString(R.string.log_image_scale_option_entry_noimage, scaleSize);
            }

            final ImmutableTriple<Integer, Integer, Boolean> scales = ImageUtils.calculateScaledImageSizes(width, height, scaleSize, scaleSize);
            String displayValue = getResources().getString(R.string.log_image_scale_option_entry, scales.left, scales.middle);
            if (scaleSize < 0) {
                displayValue += " (" + getResources().getString(R.string.log_image_scale_option_noscaling) + ")";
            }
            return displayValue;
        });

    }

    private void editExternal() {
        ensureImageEditCopy();
        final Intent intent = ImageUtils.createExternalEditImageIntent(this, image.getUri());
        startActivityForResult(Intent.createChooser(intent, null), RC_EDIT_IMAGE_EXTERNAL);
    }

    private void rotateBy(final float degree) {
        final float rotateDegree = binding.imagePreview.getRotationY() == 0 ? degree : -degree;
        binding.imagePreview.animate().setDuration(ANIMATION_DURATION_IN_MS).rotationBy(rotateDegree)
                .withStartAction(this::startAnimatedManipulation)
                .withEndAction(() -> endAnimatedManipulation(b -> ImageUtils.rotateBitmap(b, degree))).start();
    }

    private void flipHorizontal() {
        binding.imagePreview.animate().setDuration(ANIMATION_DURATION_IN_MS).rotationYBy(180)
                .withStartAction(this::startAnimatedManipulation)
                .withEndAction(() -> endAnimatedManipulation(b -> ImageUtils.flipBitmap(b, true, false))).start();
    }

    private void startAnimatedManipulation() {
        enableImageEditActions(false);
    }


    private void endAnimatedManipulation(final Func1<Bitmap, Bitmap> fct) {
        final Uri targetUri = ImageUtils.createLocalLogImageUri(geocode);
        ImageUtils.manipulateImageAsBitmap(image.getUri(), targetUri, fct);
        setImageTo(targetUri);
        enableImageEditActions(true);
        updateScaleValueDisplay();
        //do NOT call "loadImagePreview()" -> the image is already current due to animation
    }
}
