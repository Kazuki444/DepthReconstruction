package com.kazuki.depthreconstruction.activity;

import androidx.annotation.NonNull;
import androidx.appcompat.app.AppCompatActivity;

import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.Toast;

import com.google.ar.core.Anchor;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Session;
import com.google.ar.core.TrackingState;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableDeviceNotCompatibleException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.ar.core.exceptions.UnavailableUserDeclinedInstallationException;
import com.kazuki.depthreconstruction.R;
import com.kazuki.depthreconstruction.helper.CameraPermissionHelper;
import com.kazuki.depthreconstruction.helper.DepthSettings;
import com.kazuki.depthreconstruction.helper.DisplayRotationHelper;
import com.kazuki.depthreconstruction.helper.FullScreenHelper;
import com.kazuki.depthreconstruction.helper.SnackbarHelper;
import com.kazuki.depthreconstruction.helper.TapHelper;
import com.kazuki.depthreconstruction.helper.TrackingStateHelper;
import com.kazuki.depthreconstruction.rendering.BackgroundRenderer;
import com.kazuki.depthreconstruction.rendering.ObjectRenderer;
import com.kazuki.depthreconstruction.rendering.Texture;

import java.io.IOException;
import java.util.ArrayList;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class CastShadowActivity extends AppCompatActivity implements GLSurfaceView.Renderer {
  private static final String TAG = CastShadowActivity.class.getSimpleName();

  private GLSurfaceView surfaceView;

  private boolean installRequested;

  private Session session;
  private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
  private DisplayRotationHelper displayRotationHelper;
  private final TrackingStateHelper trackingStateHelper = new TrackingStateHelper(this);
  private TapHelper tapHelper;

  private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
  private final ObjectRenderer virtualObject = new ObjectRenderer();
  private final Texture depthTexture = new Texture();
  private boolean calculateUVTransform = true;

  private final DepthSettings depthSettings = new DepthSettings();
  private boolean[] settingsMenuDialogCheckboxes;

  // Temporary matrix allocated here to reduce number of allocations for each frame.
  private final float[] anchorMatrix = new float[16];
  private static final float[] DEFAULT_COLOR = new float[]{0f, 0f, 0f, 0f};

  private static final String SEARCHING_PLANE_MESSAGE = "Searching for surfaces...";

  // Anchors created from taps used for object placing with a given color.
  private static class ColoredAnchor {
    public final Anchor anchor;
    public final float[] color;

    public ColoredAnchor(Anchor a, float[] color4f) {
      this.anchor = a;
      this.color = color4f;
    }
  }

  private final ArrayList<ColoredAnchor> anchors = new ArrayList<>();

  // Switch is checked
  private boolean enableDepthSwitchIsChecked = false;
  private boolean inpaintDepthSwitchIsChecked = false;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_cast_shadow);
    surfaceView = findViewById(R.id.surfaceview_cast_shadow);
    displayRotationHelper = new DisplayRotationHelper(this);

    // Set up tap listener
    tapHelper = new TapHelper(this);
    surfaceView.setOnTouchListener(tapHelper);

    // Set up renderer
    surfaceView.setPreserveEGLContextOnPause(true);
    surfaceView.setEGLContextClientVersion(3);
    surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0);
    surfaceView.setRenderer(this);
    surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    surfaceView.setWillNotDraw(false);

    installRequested = false;
    calculateUVTransform = true;

    depthSettings.onCreate(this);
    Switch enableDepthSwitch = findViewById(R.id.switch_enable_depth);
    enableDepthSwitch.setOnCheckedChangeListener(this::onEnableDepthSwitchChanged);
    Switch inpaintDepthSwitch = findViewById(R.id.switch_inpaint_depth);
    inpaintDepthSwitch.setOnCheckedChangeListener(this::onInpaintDepthSwitchChanged);
  }

  @Override
  protected void onResume() {
    super.onResume();

    if (session == null) {
      Exception exception = null;
      String message = null;
      try {
        switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
          case INSTALL_REQUESTED:
            installRequested = true;
            return;
          case INSTALLED:
            break;
        }

        // ARCore requires camera permissions to operate. If we did not yet obtain runtime
        // permission on Android M and above, now is a good time to ask the user for it.
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
          CameraPermissionHelper.requestCameraPermission(this);
          return;
        }

        // Create the mission.
        session = new Session(this);
        Config config = session.getConfig();
        if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
          config.setDepthMode(Config.DepthMode.AUTOMATIC);
        } else {
          config.setDepthMode(Config.DepthMode.DISABLED);
        }
        session.configure(config);
      } catch (UnavailableArcoreNotInstalledException | UnavailableUserDeclinedInstallationException e) {
        message = "Please install ARCore.";
        exception = e;
      } catch (UnavailableApkTooOldException e) {
        message = "Please update ARCore.";
        exception = e;
      } catch (UnavailableSdkTooOldException e) {
        message = "Please update this app";
        exception = e;
      } catch (UnavailableDeviceNotCompatibleException e) {
        message = "This device does not support AR.";
        exception = e;
      } catch (Exception e) {
        message = "Failed to create AR session.";
        exception = e;
      }

      if (message != null) {
        messageSnackbarHelper.showError(this, message);
        Log.e(TAG, "Exception creating session", exception);
        return;
      }
    }

    try {
      session.resume();
    } catch (CameraNotAvailableException e) {
      messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app");
      session = null;
      return;
    }

    surfaceView.onResume();
    displayRotationHelper.onResume();
  }

  @Override
  public void onPause() {
    super.onPause();
    if (session != null) {
      displayRotationHelper.onPause();
      surfaceView.onPause();
      session.pause();
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, @NonNull String[] permissions, @NonNull int[] grantResults) {
    super.onRequestPermissionsResult(requestCode, permissions, grantResults);
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      Toast.makeText(this, "Camerapermission is need to run this application", Toast.LENGTH_LONG)
              .show();
      if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
        // Permission denied with checking "Do not ask again".
        CameraPermissionHelper.launchPermissionSettings(this);
      }
      finish();
    }
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
  }

  @Override
  public void onSurfaceCreated(GL10 gl10, EGLConfig eglConfig) {
    GLES30.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

    // Prepare the rendering objects.
    try {
      // Create the texture and pass it to ARCore session to be filled during update()
      depthTexture.createOnGlThread();
      backgroundRenderer.createOnGlThread(this, depthTexture.getTextureId());

      virtualObject.createOnGlThread(this, "models/andy.obj", "models/andy.png");
      virtualObject.setBlendMode(ObjectRenderer.BlendMode.AlphaBlending);
      virtualObject.setDepthTexture(
              depthTexture.getTextureId(), depthTexture.getWidth(), depthTexture.getHeight());
      virtualObject.setMaterialProperties(0.0f, 2.0f, 0.5f, 6.0f);
    } catch (IOException e) {
      Log.e(TAG, "Failed to read an asset file", e);
    }
  }

  @Override
  public void onSurfaceChanged(GL10 gl10, int width, int height) {
    displayRotationHelper.onSurfaceChanged(width, height);
    GLES30.glViewport(0,0,width,height);
  }

  @Override
  public void onDrawFrame(GL10 gl10) {
    // Clear screen to notify driver it should not load any pixels from previous frame
    GLES30.glClear(GLES30.GL_COLOR_BUFFER_BIT|GLES30.GL_DEPTH_BUFFER_BIT);

    if(session==null){
      return;
    }
    displayRotationHelper.updateSessionIfNeeded(session);

    try {
      session.setCameraTextureName(backgroundRenderer.getTextureId());

      // Obtain the current frame from ARSession.
      Frame frame=session.update();
      Camera camera=frame.getCamera();

      if(frame.hasDisplayGeometryChanged()||calculateUVTransform){
        // The UV Transform represents the transformation between screenspace in normalized units
        // and screenspace in units of pixels.  Having the size of each pixel is necessary in the
        // virtual object shader, to perform kernel-based blur effects.
        calculateUVTransform=false;
        float[] transform=getTextureTransformMatrix(frame);
        virtualObject.setUvtransformMatrix(transform);
      }

      if(session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)){
        depthTexture.updateWithDepthImageOnGlThread(frame);
      }

      // Handle one tap per frame
      handleTap(frame,camera);

      // If frame is ready, render camera preview image to the GL surface.
      backgroundRenderer.draw(frame,enableDepthSwitchIsChecked);

      // Keep the screen unlocked while tracking, but allow it to lock when tracking stops.
      trackingStateHelper.updateKeepScreenOnFlag(camera.getTrackingState());

      // If not tracking, do not draw 3D objects, show tracking failure reason instead.
      if(camera.getTrackingState()== TrackingState.PAUSED){
        messageSnackbarHelper.showMessage(
                this,TrackingStateHelper.getTrackingFailureReasonString(camera));
        return;
      }

      // Get projection matrix
      float[] projmtx=new float[16];
      camera.getProjectionMatrix(projmtx,0,0.1f,100.0f);

      // Get camera matrix
      float[] viewmtx=new float[16];
      camera.getViewMatrix(viewmtx,0);

      // Compute lighting from average intensity of image.
      // The first three components are color scaling factors.
      // The last one is the average pixel intensity gamma space.
      final float[] colorCorrectionRgba=new float[4];
      frame.getLightEstimate().getColorCorrection(colorCorrectionRgba,0);

      // No tracking error at the point. If we detected any plane, then hide the
      // message UI, otherwise shoe searchingPlane message.
      if(hasTrackingPlane()){
        messageSnackbarHelper.hide(this);
      }else{
        messageSnackbarHelper.showMessage(this,SEARCHING_PLANE_MESSAGE);
      }

      // Visualize anchors created by touch
      float scaleFactor=1.0f;
      virtualObject.setUseDepthForOcclusion(this,enableDepthSwitchIsChecked);
      for(ColoredAnchor coloredAnchor:anchors){
        if(coloredAnchor.anchor.getTrackingState()!=TrackingState.TRACKING){
          continue;
        }

        // Get the current pose of an Anchors in world space.
        coloredAnchor.anchor.getPose().toMatrix(anchorMatrix,0);

        // Update and draw the model and its shadow.
        virtualObject.updateModelMatrix(anchorMatrix,scaleFactor);
        virtualObject.draw(viewmtx,projmtx,colorCorrectionRgba,coloredAnchor.color);
      }
    }catch (Throwable t){
      // Avoid crashing the application due to unhandled exceptions
      Log.e(TAG,"Exception on the OpenGL thread",t);
    }
  }

  //
  /**
   * Swich
   */
  private void onEnableDepthSwitchChanged(CompoundButton button, boolean isChecked) {
    enableDepthSwitchIsChecked = isChecked;
  }

  private void onInpaintDepthSwitchChanged(CompoundButton button, boolean isChecked) {
    inpaintDepthSwitchIsChecked = isChecked;
  }



































}
