/*
 * Copyright (C) 2016 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.android.cameraview;

import android.annotation.TargetApi;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageFormat;
import android.graphics.Matrix;
import android.graphics.SurfaceTexture;
import android.hardware.usb.UsbDevice;
import android.media.CamcorderProfile;
import android.util.Log;
import android.view.Surface;
import android.os.Handler;
import android.os.Looper;
import android.widget.Toast;
import com.facebook.react.uimanager.ThemedReactContext;
import com.serenegiant.usbcameracommon.UVCCameraHandler;
import com.serenegiant.usb.CameraDialog;
import com.serenegiant.usb.USBMonitor;
import com.serenegiant.usb.USBMonitor.OnDeviceConnectListener;
import com.serenegiant.usb.USBMonitor.UsbControlBlock;
import com.serenegiant.usb.UVCCamera;
import com.serenegiant.widget.UVCCameraTextureView;

import java.io.ByteArrayOutputStream;
import java.util.Set;

@SuppressWarnings("MissingPermission")
@TargetApi(21)
class CameraUvc extends CameraViewImpl {

    private static final String TAG = "CameraUvc";

    /**
     * set true if you want to record movie using MediaSurfaceEncoder
     * (writing frame data into Surface camera from MediaCodec
     *  by almost same way as USBCameratest2)
     * set false if you want to record movie using MediaVideoEncoder
     */
    private static final boolean USE_SURFACE_ENCODER = false;
    /**
     * preview resolution(width)
     * if your camera does not support specific resolution and mode,
     * {@link UVCCamera#setPreviewSize(int, int, int)} throw exception
     */
    private static final int PREVIEW_WIDTH = 1920;
    /**
     * preview resolution(height)
     * if your camera does not support specific resolution and mode,
     * {@link UVCCamera#setPreviewSize(int, int, int)} throw exception
     */
    private static final int PREVIEW_HEIGHT = 1080;
    /**
     * preview mode
     * if your camera does not support specific resolution and mode,
     * {@link UVCCamera#setPreviewSize(int, int, int)} throw exception
     * 0:YUYV, other:MJPEG
     */
    private static final int PREVIEW_MODE = 0;
    /**
     * for accessing USB
     */
    private USBMonitor mUSBMonitor;

    private boolean mIsNew = true;
    private UsbControlBlock mCtrlBlock;

    /**
     * Handler to execute camera related methods sequentially on private thread
     */
    private UVCCameraHandler mCameraHandler;
    /**
     * for camera preview display
     */
    private UVCCameraTextureView mUVCCameraView;

    private ThemedReactContext mContext;

    private final UVCCameraHandler.CameraCallback mCameraDeviceCallback
            = new UVCCameraHandler.CameraCallback() {
        @Override
        public void onOpen(){
            mCallback.onCameraOpened();
            startCaptureSession();
        }
        @Override
        public void onClose(){
            mCallback.onCameraClosed();
        }
        @Override
        public void onStartPreview(){
            // some USB camera will not return onPictureTaken and onVideoRecorded by uncomment some of bellow, test them by your own
            // updateAutoFocus();
            // updateFlash();
            // updateFocusDepth();
            // updateWhiteBalance();
            // updateZoom();
        }
        @Override
        public void onStopPreview(){}
        @Override
        public void onStartRecording(){}
        @Override
        public void onStopRecording(){}
        @Override
        public void onPictureTaken(Bitmap bitmap){
            Matrix matrix = new Matrix();
            int width = bitmap.getWidth();
            int height = bitmap.getHeight();
            if (mDisplayOrientation % 180 == 90) {
                matrix.postScale((float) height / (float) width, (float) width / (float) height);
                matrix.postRotate(mDisplayOrientation - 180);
            }
            Bitmap bmp = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true);
            // if (mImageFormat == ImageFormat.JPEG) {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                bmp.compress(Bitmap.CompressFormat.JPEG, 100, baos);
                mCallback.onPictureTaken(baos.toByteArray());
            // } else { // TODO: convert bmp to ImageFormat.YUV_420_888 ?
            //     mCallback.onFramePreview(bmp, bmp.getWidth(), bmp.getHeight(), mDisplayOrientation);
            // }
        }
        @Override
        public void onVideoRecorded(String path){
            mCallback.onVideoRecorded(path);
        }
        @Override
        public void onError(final Exception e){
            Toast.makeText(mContext.getCurrentActivity(), "errorException: " + e, Toast.LENGTH_SHORT).show();
            Log.e(TAG, "errorException: ", e);
        }
    };

    private int mImageFormat;

    private boolean mIsRecording;

    private final SizeMap mPreviewSizes = new SizeMap();

    private int mFacing;

    private AspectRatio mAspectRatio = Constants.DEFAULT_ASPECT_RATIO;

    private AspectRatio mInitialRatio;

    private boolean mAutoFocus;

    private int mFlash;

    private int mDisplayOrientation;

    private float mFocusDepth;

    private float mZoom;

    private int mWhiteBalance;

    private boolean mIsScanning;

    private Surface mPreviewSurface;

    CameraUvc(Callback callback, PreviewImpl preview, Context context) {
        super(callback, preview);
        mUVCCameraView = (UVCCameraTextureView) preview.getView();
        mUVCCameraView.setAspectRatio(PREVIEW_WIDTH / (float)PREVIEW_HEIGHT);

        mContext = (ThemedReactContext) context;

        mUSBMonitor = new USBMonitor(mContext.getCurrentActivity(), mOnDeviceConnectListener);
        mCameraHandler = UVCCameraHandler.createHandler(mContext.getCurrentActivity(), mUVCCameraView,
          USE_SURFACE_ENCODER ? 0 : 1, PREVIEW_WIDTH, PREVIEW_HEIGHT, PREVIEW_MODE);

        mCameraHandler.addCallback(mCameraDeviceCallback);

        mImageFormat = mIsScanning ? ImageFormat.YUV_420_888 : ImageFormat.JPEG;
        mPreview.setCallback(new PreviewImpl.Callback() {
            @Override
            public void onSurfaceChanged() {
                // if (mCameraHandler.isPreviewing()) {
                //     if (mUVCCameraView != null)
                //         mUVCCameraView.onPause();
                //     stop();
                //     // stopCaptureSession();
                // }
                startCaptureSession();
            }

            @Override
            public void onSurfaceDestroyed() {
                stop();
            }
        });
    }

    private final OnDeviceConnectListener mOnDeviceConnectListener = new OnDeviceConnectListener() {
        @Override
        public void onAttach(final UsbDevice device) {
            // Toast.makeText(mContext.getCurrentActivity(), "USB_DEVICE_ATACHED", Toast.LENGTH_SHORT).show();
            if (!mCameraHandler.isOpened()) {
                CameraDialog.showDialog(mContext.getCurrentActivity(), mUSBMonitor);
            }
        }

        @Override
        public void onConnect(final UsbDevice device, final UsbControlBlock ctrlBlock, final boolean createNew) {
            // Toast.makeText(mContext.getCurrentActivity(), "onConnect", Toast.LENGTH_SHORT).show();
            mCtrlBlock = ctrlBlock;
            mCameraHandler.open(mCtrlBlock);
        }

        @Override
        public void onDisconnect(final UsbDevice device, final UsbControlBlock ctrlBlock) {
            Toast.makeText(mContext.getCurrentActivity(), "onDisconnect", Toast.LENGTH_SHORT).show();
            if (mCameraHandler != null) {
                Handler handler = new Handler(Looper.getMainLooper());
                handler.postDelayed(new Runnable() {
                    @Override
                    public void run() {
                        if (mCameraHandler.isPreviewing()) {
                            stopCaptureSession();
                        }
                        mCameraHandler.close();
                    }
                }, 0);
            }
        }

        @Override
        public void onDettach(final UsbDevice device) {
            Toast.makeText(mContext.getCurrentActivity(), "USB_DEVICE_DETACHED", Toast.LENGTH_SHORT).show();
        }

        @Override
        public void onCancel(final UsbDevice device) {}
    };

    @Override
    boolean start() {
        // if (!chooseCameraIdByFacing()) {
        //     mAspectRatio = mInitialRatio;
        //     return false;
        // }
        // setAspectRatio(mInitialRatio);
        // mInitialRatio = null;

        if (mIsNew) {
            mUSBMonitor.register();
            mIsNew = false;
        }
        if (mCtrlBlock != null) {
            mCameraHandler.open(mCtrlBlock);
        }
        // if (mUVCCameraView != null)
        //     mUVCCameraView.onResume();

        return true;
    }

    @Override
    void stop() {
        if (mCameraHandler != null) {
            if (mIsRecording) {
                mCameraHandler.stopRecording();
                mIsRecording = false;
            }
            mCameraHandler.close();
        }
        // if (mUVCCameraView != null)
        //     mUVCCameraView.onPause();
    }

    @Override
    void destroy() {
        if (mCameraHandler != null) {
            mCameraHandler.release();
            mCameraHandler = null;
        }
        if (mUSBMonitor != null) {
            mUSBMonitor.destroy();
            mUSBMonitor = null;
        }
        mUVCCameraView = null;
    }

    @Override
    boolean isCameraOpened() {
        return mCameraHandler.isOpened();
    }

    @Override
    void setFacing(int facing) {
        if (mFacing == facing) {
            return;
        }
        mFacing = facing;
        if (isCameraOpened()) {
            stop();
            start();
        }
    }

    @Override
    int getFacing() {
        return mFacing;
    }

    @Override
    Set<AspectRatio> getSupportedAspectRatios() {
        return mPreviewSizes.ratios();
    }

    @Override
    boolean setAspectRatio(AspectRatio ratio) {
        if (ratio != null && mPreviewSizes.isEmpty()) {
            mInitialRatio = ratio;
            return false;
        }
        if (ratio == null || ratio.equals(mAspectRatio) ||
                !mPreviewSizes.ratios().contains(ratio)) {
            // TODO: Better error handling
            return false;
        }
        mAspectRatio = ratio;
        if (mCameraHandler.isPreviewing()) {
            stopCaptureSession();
            startCaptureSession();
        }
        return true;
    }

    @Override
    AspectRatio getAspectRatio() {
        return mAspectRatio;
    }

    @Override
    void setAutoFocus(boolean autoFocus) {
        if (mAutoFocus == autoFocus) {
            return;
        }
        mAutoFocus = autoFocus;
        if (isCameraOpened()) {
            updateAutoFocus();
            startCaptureSession();
        }
    }

    @Override
    boolean getAutoFocus() {
        return mAutoFocus;
    }

    @Override
    void setFlash(int flash) {
        if (mFlash == flash) {
            return;
        }
        int saved = mFlash;
        mFlash = flash;
        if (isCameraOpened()) {
            updateFlash();
            startCaptureSession();
        }
    }

    @Override
    int getFlash() {
        return mFlash;
    }

    @Override
    void takePicture() {
        captureStillPicture();
    }

    @Override
    boolean record(String path, int maxDuration, int maxFileSize, boolean recordAudio, CamcorderProfile profile) {
        if (!mIsRecording) {
            mCameraHandler.startRecording();
            mIsRecording = true;
            return true;
        }
        return false;
    }

    @Override
    void stopRecording() {
        if (mIsRecording) {
            stopMediaRecorder();

            if (mCameraHandler.isPreviewing()) {
                stopCaptureSession();
            }
            startCaptureSession();
        }
    }

    @Override
    public void setFocusDepth(float value) {
        if (mFocusDepth == value) {
            return;
        }
        float saved = mFocusDepth;
        mFocusDepth = value;
        if (mCameraHandler.isPreviewing()) {
            updateFocusDepth();
            startCaptureSession();
        }
    }

    @Override
    float getFocusDepth() {
        return mFocusDepth;
    }

    @Override
    public void setZoom(float zoom) {
        if (mZoom == zoom) {
            return;
        }
        float saved = mZoom;
        mZoom = zoom;
        if (mCameraHandler.isPreviewing()) {
            updateZoom();
            startCaptureSession();
        }
    }

    @Override
    float getZoom() {
        return mZoom;
    }

    @Override
    public void setWhiteBalance(int whiteBalance) {
        if (mWhiteBalance == whiteBalance) {
            return;
        }
        int saved = mWhiteBalance;
        mWhiteBalance = whiteBalance;
        if (mCameraHandler.isPreviewing()) {
            updateWhiteBalance();
            startCaptureSession();
       }
    }

    @Override
    public int getWhiteBalance() {
        return mWhiteBalance;
    }

    @Override
    void setScanning(boolean isScanning) {
        if (mIsScanning == isScanning) {
            return;
        }
        mIsScanning = isScanning;
        if (!mIsScanning) {
            mImageFormat = ImageFormat.JPEG;
        } else {
            mImageFormat = ImageFormat.YUV_420_888;
        }
        if (mCameraHandler.isPreviewing()) {
            stopCaptureSession();
        }
        startCaptureSession();
    }

    @Override
    boolean getScanning() {
        return mIsScanning;
    }

    @Override
    void setDisplayOrientation(int displayOrientation) {
        mDisplayOrientation = displayOrientation;
        mPreview.setDisplayOrientation(mDisplayOrientation);
    }

    /**
     * <p>Starts a capture session for camera preview.</p>
     */
    void startCaptureSession() {
        if (!isCameraOpened() || !mPreview.isReady()) {
            return;
        }
        mCameraHandler.startPreview(getPreviewSurface());
    }

    void stopCaptureSession() {
        mCameraHandler.stopPreview();
    }

    public Surface getPreviewSurface() {
        if (mPreviewSurface != null) {
            return mPreviewSurface;
        }
        return mPreview.getSurface();
    }

    @Override
    public void setPreviewTexture(SurfaceTexture surfaceTexture) {
        if (surfaceTexture != null) {
            Surface previewSurface = new Surface(surfaceTexture);
            mPreviewSurface = previewSurface;
        } else {
            mPreviewSurface = null;
        }

        // it may be called from another thread, so make sure we're in main looper
        Handler handler = new Handler(Looper.getMainLooper());
        handler.post(new Runnable() {
            @Override
            public void run() {
                if (mCameraHandler.isPreviewing()) {
                    stopCaptureSession();
                }
                startCaptureSession();
            }
        });
    }

    @Override
    public Size getPreviewSize() {
        return new Size(mUVCCameraView.getWidth(), mUVCCameraView.getHeight());
    }

    /**
     * Updates the internal state of auto-focus to {@link #mAutoFocus}.
     */
    void updateAutoFocus() {
        if (mAutoFocus) {
            mAutoFocus = mCameraHandler.getAutoFocus();
        }
        mCameraHandler.setAutoFocus(mAutoFocus);
    }

    /**
     * Updates the internal state of flash to {@link #mFlash}.
     */
    void updateFlash() {
        switch (mFlash) {
            case Constants.FLASH_OFF:
                break;
            case Constants.FLASH_ON:
                break;
            case Constants.FLASH_TORCH:
                break;
            case Constants.FLASH_AUTO:
                break;
            case Constants.FLASH_RED_EYE:
                break;
        }
    }

    /**
     * Updates the internal state of focus depth to {@link #mFocusDepth}.
     */
    void updateFocusDepth() {
        if (mAutoFocus) {
          return;
        }
        mCameraHandler.setValue(UVCCamera.CTRL_FOCUS_REL, (int) (mFocusDepth * 100));
    }

    /**
     * Updates the internal state of zoom to {@link #mZoom}.
     */
    void updateZoom() {
        mCameraHandler.setValue(UVCCamera.CTRL_ZOOM_REL, (int) (mZoom * 100));
    }

    /**
     * Updates the internal state of white balance to {@link #mWhiteBalance}.
     */
    void updateWhiteBalance() {
        mCameraHandler.setAutoWhiteBlance(true);
    }

    /**
     * Captures a still picture.
     */
    void captureStillPicture() {
        if (mCameraHandler.isOpened()) {
            mCameraHandler.captureStill();
        }
    }

    private void stopMediaRecorder() {
        mIsRecording = false;
        mCameraHandler.stopRecording();
    }
}
