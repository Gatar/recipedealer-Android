package com.gatar.recipedealer.ui

import android.content.ActivityNotFoundException
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.FileProvider
import com.gatar.recipedealer.R
import com.gatar.recipedealer.api.ApiResult
import com.gatar.recipedealer.container
import com.gatar.recipedealer.inbox.Inbox
import com.gatar.recipedealer.recipes.RecipePhoto
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private fun authority(packageName: String) = "$packageName.files"

/** A recipe photo from the gallery or the camera, uploaded at once; see [rememberPhotoPicker]. */
class PhotoPicker(
    val fromGallery: () -> Unit,
    val fromCamera: () -> Unit,
    val uploading: Boolean,
    val error: String?,
)

/**
 * Gallery (system photo picker, no permission) or camera (the camera app, no permission) → [RecipePhoto.prepare]
 * (upright, ≤ 1600 px JPEG) → `POST /v1/blobs` → [onUploaded] with `blob:<sha256>` for `images[0]`. The recipe editor
 * saves it with the edit; a capture keeps it in `recipe.images[0]`, so the server makes it the new recipe's photo.
 * Null without a server (local mode): the photo has to live on the server.
 */
@Composable
fun rememberPhotoPicker(onUploaded: (String) -> Unit): PhotoPicker? {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val uploaded by rememberUpdatedState(onUploaded)
    var uploading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var cameraFile by rememberSaveable { mutableStateOf<String?>(null) }

    fun upload(uri: Uri, temp: File? = null) = scope.launch {
        uploading = true
        error = null
        val jpeg = runCatching { withContext(Dispatchers.IO) { RecipePhoto.prepare(context, uri) } }.getOrNull()
        temp?.delete()
        val r = jpeg?.let { context.container.backend()?.uploadPhoto(it) ?: Inbox.NO_BACKEND }
        uploading = false
        when {
            r == null -> error = context.getString(R.string.photo_unreadable)
            r is ApiResult.Ok -> uploaded(r.value.ref)
            isMissingRoute(r) -> error = context.getString(R.string.photo_unsupported)
            else -> error = apiErrorText(context, r)
        }
    }

    val gallery = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
        uri?.let { upload(it) }
    }
    val camera = rememberLauncherForActivityResult(ActivityResultContracts.TakePicture()) { taken ->
        val f = cameraFile?.let(::File) ?: return@rememberLauncherForActivityResult
        if (taken) upload(FileProvider.getUriForFile(context, authority(context.packageName), f), temp = f) else f.delete()
    }
    if (!context.container.settings.current.hasBackend) return null
    return PhotoPicker(
        fromGallery = { gallery.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)) },
        fromCamera = {
            val f = File(context.cacheDir, "photos/camera-${System.currentTimeMillis()}.jpg").apply { parentFile?.mkdirs() }
            cameraFile = f.path
            try {
                camera.launch(FileProvider.getUriForFile(context, authority(context.packageName), f))
            } catch (e: ActivityNotFoundException) {
                error = context.getString(R.string.photo_no_camera)
            }
        },
        uploading = uploading,
        error = error,
    )
}
