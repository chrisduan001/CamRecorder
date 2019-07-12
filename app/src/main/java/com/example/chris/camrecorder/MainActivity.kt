package com.example.chris.camrecorder

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.SurfaceTexture
import android.hardware.camera2.*
import android.media.ImageReader
import android.support.v7.app.AppCompatActivity
import android.os.Bundle
import android.os.Environment
import android.os.Handler
import android.os.HandlerThread
import android.support.v4.app.ActivityCompat
import android.util.Size
import android.view.Surface
import android.view.TextureView
import android.widget.Toast
import kotlinx.android.synthetic.main.activity_main.*
import java.io.File
import java.io.FileOutputStream
import java.util.*
import java.util.Calendar.*

class MainActivity : AppCompatActivity() {
    private var cameraDevice: CameraDevice? = null
    private var imgDimension: Size? = null
    private var captureSession: CameraCaptureSession? = null

    private var previewBuilder: CaptureRequest.Builder? = null

    private var bgThread: HandlerThread? = null

    private var bgHandler: Handler? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        camera_view.surfaceTextureListener = textureListener

        btn_record.setOnClickListener { takePhoto() }

    }

    override fun onResume() {
        super.onResume()

        startBackgroundThread()
    }

    override fun onPause() {
        super.onPause()

        closeCamera()
        stopBgThread()
    }

    private val textureListener = object : TextureView.SurfaceTextureListener {
        override fun onSurfaceTextureSizeChanged(sureface: SurfaceTexture?, width: Int, height: Int) {
        }

        override fun onSurfaceTextureUpdated(surface: SurfaceTexture?) {
        }

        override fun onSurfaceTextureDestroyed(surface: SurfaceTexture?): Boolean {
            return false
        }

        override fun onSurfaceTextureAvailable(surface: SurfaceTexture?, width: Int, height: Int) {
            openCamera()
        }
    }

    private val camStateCallback = object : CameraDevice.StateCallback() {
        override fun onOpened(camera: CameraDevice?) {
            cameraDevice = camera

            createPreview()
        }

        override fun onDisconnected(camera: CameraDevice?) {
            cameraDevice?.close()
        }

        override fun onError(camera: CameraDevice?, error: Int) {
            cameraDevice?.close()
            cameraDevice = null
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession?, request: CaptureRequest?, result: TotalCaptureResult?) {
            super.onCaptureCompleted(session, request, result)
        }
    }

    private fun takePhoto() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val characteristics = manager.getCameraCharacteristics(cameraDevice?.id)
        val jpegSize = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)?.getOutputSizes(ImageFormat.JPEG)

        var width = 640
        var height = 480

        jpegSize?.let {
            if (it.isNotEmpty()) {
                width = it[0].width
                height = it[0].height
            }
        }

        val reader = ImageReader.newInstance(640, 480, ImageFormat.JPEG, 1)
        val outputSurface = arrayListOf<Surface>(
                reader.surface,
                Surface(camera_view.surfaceTexture)
        )

        val captureBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
        captureBuilder?.addTarget(reader.surface)
        captureBuilder?.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)

        val rotation = windowManager.defaultDisplay.rotation
        captureBuilder?.set(CaptureRequest.JPEG_ORIENTATION, ORIENTATIONS[rotation])
        val file = File("${Environment.getExternalStorageDirectory()}" +
                "/${Calendar.getInstance().get(HOUR_OF_DAY)}_${Calendar.getInstance().get(MINUTE)}_${Calendar.getInstance().get(SECOND)}.jpg")

        val readerListener = ImageReader.OnImageAvailableListener {

            val image = it.acquireLatestImage()
            val buffer = image.planes[0].buffer
            val bytes = ByteArray(buffer.capacity())
            buffer.get(bytes)

            val output = FileOutputStream(file)
            output.use { it.write(bytes) }
//            output.write(bytes)

            image.close()
        }

        reader.setOnImageAvailableListener(readerListener, bgHandler)

        val captureListener = object : CameraCaptureSession.CaptureCallback() {
            override fun onCaptureCompleted(session: CameraCaptureSession?, request: CaptureRequest?, result: TotalCaptureResult?) {
                super.onCaptureCompleted(session, request, result)
//                createPreview()
                Toast.makeText(this@MainActivity, "image saved", Toast.LENGTH_SHORT).show()
            }
        }
//
//        val texture = camera_view.surfaceTexture
//        texture.setDefaultBufferSize(imgDimension!!.width, imgDimension!!.height)
//
//        val surface = Surface(texture)

        cameraDevice?.createCaptureSession(outputSurface, object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(camera: CameraCaptureSession?) {
                camera?.capture(captureBuilder?.build(), captureListener, bgHandler)
            }

            override fun onConfigureFailed(camera: CameraCaptureSession?) {
                Toast.makeText(this@MainActivity, "Capture photo config failed", Toast.LENGTH_SHORT).show()
            }
        }, bgHandler)
    }

    private fun openCamera() {
        val manager = getSystemService(Context.CAMERA_SERVICE) as CameraManager
        val camId = manager.cameraIdList[0]
        val characters = manager.getCameraCharacteristics(camId)
        val configMap = characters.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP)

        imgDimension = configMap.getOutputSizes(SurfaceTexture::class.java)[0]

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.WRITE_EXTERNAL_STORAGE) != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(this, arrayOf(Manifest.permission.CAMERA, Manifest.permission.WRITE_EXTERNAL_STORAGE), 1000)
            return
        }

        manager.openCamera(camId, camStateCallback, null)
    }

    private fun createPreview() {
        closePreviewSession()

        val texture = camera_view.surfaceTexture
        texture.setDefaultBufferSize(imgDimension!!.width, imgDimension!!.height)

        val surface = Surface(texture)
        previewBuilder = cameraDevice?.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW)
        previewBuilder?.addTarget(surface)
        cameraDevice?.createCaptureSession(listOf(surface), object : CameraCaptureSession.StateCallback() {
            override fun onConfigured(camera: CameraCaptureSession) {
                captureSession = camera

                updatePreview()
            }

            override fun onConfigureFailed(camera: CameraCaptureSession?) {
                Toast.makeText(this@MainActivity, "Preview config failed", Toast.LENGTH_SHORT).show()
            }
        }, null)
    }

    private fun updatePreview() {
        previewBuilder?.set(CaptureRequest.CONTROL_MODE, CameraMetadata.CONTROL_MODE_AUTO)
        captureSession?.setRepeatingRequest(previewBuilder?.build(), null, bgHandler)
    }

    private fun closePreviewSession() {
        captureSession?.close()
        captureSession = null
    }

    private fun startBackgroundThread() {
        bgThread = HandlerThread("Camera Capture")
        bgThread?.start()
        bgHandler = Handler(bgThread?.looper)
    }

    private fun stopBgThread() {
        bgThread?.quitSafely()

        try {
            bgThread?.join()
            bgThread = null
            bgHandler = null
        } catch (e: InterruptedException) {}
    }

    private fun closeCamera() {
        closePreviewSession()

        cameraDevice?.close()
        cameraDevice = null
    }

    companion object {
        val ORIENTATIONS = mapOf(Pair(Surface.ROTATION_0, 90),
                Pair(Surface.ROTATION_90, 0),
                Pair(Surface.ROTATION_180, 270),
                Pair(Surface.ROTATION_270, 180))
    }
}
