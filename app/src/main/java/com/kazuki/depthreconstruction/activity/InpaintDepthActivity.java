package com.kazuki.depthreconstruction.activity;

import androidx.appcompat.app.AppCompatActivity;

import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.os.Bundle;
import android.util.Log;
import android.widget.CompoundButton;
import android.widget.Switch;
import android.widget.Toast;

import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Frame;
import com.google.ar.core.Session;
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
import com.kazuki.depthreconstruction.rendering.BackgroundRenderer;
import com.kazuki.depthreconstruction.rendering.InpaintRenderer;
import com.kazuki.depthreconstruction.rendering.Texture;

import java.io.IOException;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

public class InpaintDepthActivity extends AppCompatActivity implements GLSurfaceView.Renderer {
  private static final String TAG = InpaintDepthActivity.class.getSimpleName();

  private GLSurfaceView surfaceView;

  private boolean installRequested;

  private Session session;
  private final SnackbarHelper messageSnackbarHelper = new SnackbarHelper();
  private DisplayRotationHelper displayRotationHelper;

  private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();
  private final Texture depthTexture = new Texture();

  private final DepthSettings depthSettings = new DepthSettings();
  private Switch depthModeSwitch;

  private final InpaintRenderer inpaintRenderer = new InpaintRenderer();
  private Switch inpaintModeSwitch;
  private boolean isInpaintModeChecked;

  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    setContentView(R.layout.activity_inpaint_depth);
    surfaceView = findViewById(R.id.surfaceview_inpaint_depth);
    displayRotationHelper = new DisplayRotationHelper(this);

    //Set up renderer
    surfaceView.setPreserveEGLContextOnPause(true);
    surfaceView.setEGLContextClientVersion(3);
    surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Alpha used for plane blending.
    surfaceView.setRenderer(this);
    surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    surfaceView.setWillNotDraw(false);

    installRequested = false;

    depthSettings.onCreate(this);
    depthModeSwitch = (Switch) findViewById(R.id.switch1);
    depthModeSwitch.setOnCheckedChangeListener(this::onDepthModeChanged);

    inpaintModeSwitch = (Switch) findViewById(R.id.switch2);
    inpaintModeSwitch.setOnCheckedChangeListener(this::onInpaintModeChanged);
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

        if (!CameraPermissionHelper.hasCameraPermission(this)) {
          CameraPermissionHelper.requestCameraPermission(this);
          return;
        }

        // Create the session.
        session = new Session(/* context= */ this);
        Config config = session.getConfig();
        if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
          config.setDepthMode(Config.DepthMode.AUTOMATIC);
        } else {
          config.setDepthMode(Config.DepthMode.DISABLED);
        }
        session.configure(config);
      } catch (UnavailableArcoreNotInstalledException
              | UnavailableUserDeclinedInstallationException e) {
        message = "Please install ARCore";
        exception = e;
      } catch (UnavailableApkTooOldException e) {
        message = "Please update ARCore";
        exception = e;
      } catch (UnavailableSdkTooOldException e) {
        message = "Please update this app";
        exception = e;
      } catch (UnavailableDeviceNotCompatibleException e) {
        message = "This device does not support AR";
        exception = e;
      } catch (Exception e) {
        message = "Failed to create AR session";
        exception = e;
      }

      if (message != null) {
        messageSnackbarHelper.showError(this, message);
        Log.e(TAG, "Exception creating session", exception);
        return;
      }
    }

    // Note that order matters - see the note in onPause(), the reverse applies here.
    try {
      session.resume();
    } catch (CameraNotAvailableException e) {
      messageSnackbarHelper.showError(this, "Camera not available. Try restarting the app.");
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
      // Note that the order matters - GLSurfaceView is paused first so that it does not try
      // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
      // still call session.update() and get a SessionPausedException.
      displayRotationHelper.onPause();
      surfaceView.onPause();
      session.pause();
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    super.onRequestPermissionsResult(requestCode, permissions, results);
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
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
    GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

    // Prepare the rendering objects
    try {
      depthTexture.createOnGlThread();
      backgroundRenderer.createOnGlThread(this, depthTexture.getTextureId());
      inpaintRenderer.createOnGlThread(this, depthTexture.getTextureId());
    } catch (IOException e) {
      Log.e(TAG, "Failed to read an asset file", e);
    }
  }

  @Override
  public void onSurfaceChanged(GL10 gl10, int width, int height) {
    displayRotationHelper.onSurfaceChanged(width, height);
    GLES20.glViewport(0, 0, width, height);
  }

  @Override
  public void onDrawFrame(GL10 gl10) {
    // Clear screen
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);

    if (session == null) {
      return;
    }

    displayRotationHelper.updateSessionIfNeeded(session);

    try {
      session.setCameraTextureName(backgroundRenderer.getTextureId());

      // Obtain the current frame from ARSession. When the configuration is set to
      // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
      // camera framerate.
      Frame frame = session.update();
      Camera camera = frame.getCamera();

      if (session.isDepthModeSupported(Config.DepthMode.AUTOMATIC)) {
        depthTexture.updateWithDepthImageOnGlThread(frame);
      }

      // If frame is ready, render camera preview image to the GL surface.
      backgroundRenderer.draw(frame, depthSettings.depthColorVisualizationEnabled());
      inpaintRenderer.draw(frame, depthSettings.depthColorVisualizationEnabled(), isInpaintModeChecked);
    } catch (Throwable t) {
      // Avoid crashing the application due to unhandled exceptions.
      Log.e(TAG, "Exception on the OpenGL thread", t);
    }
  }

  /**
   * Swich effect
   **/
  private void onDepthModeChanged(CompoundButton unusedButton, boolean isChecked) {
    depthSettings.setDepthColorVisualizationEnabled(isChecked);
  }

  private void onInpaintModeChanged(CompoundButton unusedButton, boolean isChecked) {
    isInpaintModeChecked = isChecked;
  }
}
