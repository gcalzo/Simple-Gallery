package com.simplemobiletools.gallery.activities

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.support.design.widget.Snackbar
import android.support.v4.app.ActivityCompat
import android.view.Menu
import android.view.MenuItem
import android.view.MotionEvent
import android.view.View
import com.simplemobiletools.filepicker.extensions.*
import com.simplemobiletools.gallery.Constants
import com.simplemobiletools.gallery.R
import com.simplemobiletools.gallery.Utils
import com.simplemobiletools.gallery.adapters.DirectoryAdapter
import com.simplemobiletools.gallery.asynctasks.GetDirectoriesAsynctask
import com.simplemobiletools.gallery.dialogs.ChangeSortingDialog
import com.simplemobiletools.gallery.models.Directory
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.util.*

class MainActivity : SimpleActivity(), GetDirectoriesAsynctask.GetDirectoriesListener, View.OnTouchListener, DirectoryAdapter.DirOperationsListener {
    companion object {
        private val STORAGE_PERMISSION = 1
        private val PICK_MEDIA = 2
        private val PICK_WALLPAPER = 3

        private var mSnackbar: Snackbar? = null
        lateinit var mDirs: ArrayList<Directory>
        lateinit var mToBeDeleted: ArrayList<String>

        private var mIsSnackbarShown = false
        private var mIsPickImageIntent = false
        private var mIsPickVideoIntent = false
        private var mIsGetImageContentIntent = false
        private var mIsGetVideoContentIntent = false
        private var mIsGetAnyContentIntent = false
        private var mIsSetWallpaperIntent = false
        private var mIsThirdPartyIntent = false
        private var mIsGettingDirs = false
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        mIsPickImageIntent = isPickImageIntent(intent)
        mIsPickVideoIntent = isPickVideoIntent(intent)
        mIsGetImageContentIntent = isGetImageContentIntent(intent)
        mIsGetVideoContentIntent = isGetVideoContentIntent(intent)
        mIsGetAnyContentIntent = isGetAnyContentIntent(intent)
        mIsSetWallpaperIntent = isSetWallpaperIntent(intent)
        mIsThirdPartyIntent = mIsPickImageIntent || mIsPickVideoIntent || mIsGetImageContentIntent || mIsGetVideoContentIntent ||
                mIsGetAnyContentIntent || mIsSetWallpaperIntent

        mToBeDeleted = ArrayList<String>()
        directories_holder.setOnRefreshListener({ getDirectories() })
        mDirs = ArrayList<Directory>()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        if (mIsThirdPartyIntent)
            return false

        menuInflater.inflate(R.menu.menu_main, menu)
        return true
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.sort -> {
                showSortingDialog()
                true
            }
            R.id.camera -> {
                startActivity(Intent(MediaStore.INTENT_ACTION_STILL_IMAGE_CAMERA))
                true
            }
            R.id.settings -> {
                startActivity(Intent(applicationContext, SettingsActivity::class.java))
                true
            }
            R.id.about -> {
                startActivity(Intent(applicationContext, AboutActivity::class.java))
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    override fun onResume() {
        super.onResume()
        tryloadGallery()
    }

    override fun onPause() {
        super.onPause()
        deleteDirs()
    }

    override fun onDestroy() {
        super.onDestroy()
        mConfig.isFirstRun = false
    }

    private fun tryloadGallery() {
        if (hasStoragePermission()) {
            getDirectories()
        } else {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.WRITE_EXTERNAL_STORAGE), STORAGE_PERMISSION)
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)

        if (requestCode == STORAGE_PERMISSION) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                getDirectories()
            } else {
                toast(R.string.no_permissions)
                finish()
            }
        }
    }

    private fun getDirectories() {
        if (mIsGettingDirs)
            return

        mIsGettingDirs = true
        GetDirectoriesAsynctask(applicationContext, mIsPickVideoIntent || mIsGetVideoContentIntent, mIsPickImageIntent || mIsGetImageContentIntent,
                mToBeDeleted, this).execute()
    }

    private fun showSortingDialog() {
        ChangeSortingDialog(this, true, object : ChangeSortingDialog.OnChangeSortingListener {
            override fun sortingChanged() {
                getDirectories()
            }
        })
    }

    override fun prepareForDeleting(paths: ArrayList<String>) {
        toast(R.string.deleting)
        mToBeDeleted = paths
        val deletedCnt = mToBeDeleted.size

        if (isShowingPermDialog(File(mToBeDeleted[0])))
            return

        notifyDeletion(deletedCnt)
    }

    private fun notifyDeletion(cnt: Int) {
        getDirectories()

        val res = resources
        val msg = res.getQuantityString(R.plurals.folders_deleted, cnt, cnt)
        mSnackbar = Snackbar.make(coordinator_layout, msg, Snackbar.LENGTH_INDEFINITE)
        mSnackbar!!.apply {
            setAction(res.getString(R.string.undo), undoDeletion)
            setActionTextColor(Color.WHITE)
            show()
        }
        mIsSnackbarShown = true
    }

    private fun deleteDirs() {
        if (mToBeDeleted.isEmpty())
            return

        mSnackbar?.dismiss()
        mIsSnackbarShown = false

        val updatedFiles = ArrayList<File>()
        for (delPath in mToBeDeleted) {
            val dir = File(delPath)
            if (dir.exists()) {
                val files = dir.listFiles()
                files.forEach {
                    if (it.isFile && it.isPhotoVideo()) {
                        updatedFiles.add(it)
                        deleteItem(it)
                    }
                }
                updatedFiles.add(dir)
                if (dir.listFiles().isEmpty())
                    deleteItem(dir)
            }
        }

        scanFiles(updatedFiles) {}
        mToBeDeleted.clear()
    }

    private fun deleteItem(file: File) {
        if (needsStupidWritePermissions(file.absolutePath)) {
            if (!isShowingPermDialog(file)) {
                val document = getFileDocument(file.absolutePath, mConfig.treeUri)

                // double check we have the uri to the proper file path, not some parent folder
                if (document.uri.toString().endsWith(file.absolutePath.getFilenameFromPath()))
                    document.delete()
            }
        } else {
            file.delete()
        }
    }

    private val undoDeletion = View.OnClickListener {
        mSnackbar!!.dismiss()
        mIsSnackbarShown = false
        mToBeDeleted.clear()
        getDirectories()
    }

    private fun isPickImageIntent(intent: Intent) = isPickIntent(intent) && (hasImageContentData(intent) || isImageType(intent))

    private fun isPickVideoIntent(intent: Intent) = isPickIntent(intent) && (hasVideoContentData(intent) || isVideoType(intent))

    private fun isPickIntent(intent: Intent) = intent.action == Intent.ACTION_PICK

    private fun isGetContentIntent(intent: Intent) = intent.action == Intent.ACTION_GET_CONTENT && intent.type != null

    private fun isGetImageContentIntent(intent: Intent) = isGetContentIntent(intent) &&
            (intent.type.startsWith("image/") || intent.type == MediaStore.Images.Media.CONTENT_TYPE)

    private fun isGetVideoContentIntent(intent: Intent) = isGetContentIntent(intent) &&
            (intent.type.startsWith("video/") || intent.type == MediaStore.Video.Media.CONTENT_TYPE)

    private fun isGetAnyContentIntent(intent: Intent) = isGetContentIntent(intent) && intent.type == "*/*"

    private fun isSetWallpaperIntent(intent: Intent?) = intent?.action == Intent.ACTION_SET_WALLPAPER

    private fun hasImageContentData(intent: Intent) = intent.data == MediaStore.Images.Media.EXTERNAL_CONTENT_URI

    private fun hasVideoContentData(intent: Intent) = intent.data == MediaStore.Video.Media.EXTERNAL_CONTENT_URI

    private fun isImageType(intent: Intent) = (intent.type?.startsWith("image/") == true || intent.type == MediaStore.Images.Media.CONTENT_TYPE)

    private fun isVideoType(intent: Intent) = (intent.type?.startsWith("video/") == true || intent.type == MediaStore.Video.Media.CONTENT_TYPE)

    override fun onActivityResult(requestCode: Int, resultCode: Int, resultData: Intent?) {
        if (resultCode == Activity.RESULT_OK) {
            if (requestCode == PICK_MEDIA && resultData != null) {
                Intent().apply {
                    val path = resultData.data.path
                    val uri = Uri.fromFile(File(path))
                    if (mIsGetImageContentIntent || mIsGetVideoContentIntent || mIsGetAnyContentIntent) {
                        val type = Utils.getMimeType(path)
                        setDataAndTypeAndNormalize(uri, type)
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    } else if (mIsPickImageIntent || mIsPickVideoIntent) {
                        data = uri
                        flags = Intent.FLAG_GRANT_READ_URI_PERMISSION
                    }

                    setResult(Activity.RESULT_OK, this)
                }
                finish()
            } else if (requestCode == PICK_WALLPAPER) {
                setResult(Activity.RESULT_OK)
                finish()
            }
        }
        super.onActivityResult(requestCode, resultCode, resultData)
    }

    fun itemClicked(path: String) {
        Intent(this, MediaActivity::class.java).apply {
            putExtra(Constants.DIRECTORY, path)

            if (mIsSetWallpaperIntent) {
                putExtra(Constants.SET_WALLPAPER_INTENT, true)
                startActivityForResult(this, PICK_WALLPAPER)
            } else {
                putExtra(Constants.GET_IMAGE_INTENT, mIsPickImageIntent || mIsGetImageContentIntent)
                putExtra(Constants.GET_VIDEO_INTENT, mIsPickVideoIntent || mIsGetVideoContentIntent)
                putExtra(Constants.GET_ANY_INTENT, mIsGetAnyContentIntent)
                startActivityForResult(this, PICK_MEDIA)
            }
        }
    }

    override fun gotDirectories(dirs: ArrayList<Directory>) {
        directories_holder.isRefreshing = false
        mIsGettingDirs = false
        if (dirs.toString() == mDirs.toString()) {
            return
        }
        mDirs = dirs

        val adapter = DirectoryAdapter(this, mDirs, this) {
            itemClicked(it.path)
        }
        directories_grid.adapter = adapter
        directories_grid.setOnTouchListener(this)
    }

    override fun refreshItems() {
        getDirectories()
    }

    override fun onTouch(v: View, event: MotionEvent): Boolean {
        if (mIsSnackbarShown) {
            deleteDirs()
        }

        return false
    }
}