/*
 * Copyright 2017 Google Inc. All Rights Reserved.
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.kazuki.depthreconstruction.rendering;

import android.content.Context;
import android.opengl.GLES11Ext;
import android.opengl.GLES30;
import android.opengl.GLSurfaceView;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

/**
 * This class renders the AR background from camera feed. It creates and hosts the texture given to
 * ARCore to be filled with the camera image.
 */
public class BackgroundRenderer {
  private static final String TAG = BackgroundRenderer.class.getSimpleName();

  // Shader names.
  private static final String CAMERA_VERTEX_SHADER_NAME = "shaders/screenquad.vert";
  private static final String CAMERA_FRAGMENT_SHADER_NAME = "shaders/screenquad.frag";
  private static final String DEPTH_VISUALIZER_VERTEX_SHADER_NAME =
          "shaders/background_show_depth_color_visualization.vert";
  private static final String DEPTH_VISUALIZER_FRAGMENT_SHADER_NAME =
          "shaders/background_show_depth_color_visualization.frag";

  private static final int COORDS_PER_VERTEX = 2;
  private static final int TEXCOORDS_PER_VERTEX = 2;
  private static final int FLOAT_SIZE = 4;

  private FloatBuffer quadCoords;
  private FloatBuffer quadTexCoords;

  private int cameraProgram;
  private int depthProgram;

  private int cameraPositionAttrib;
  private int cameraTexCoordAttrib;
  private int cameraTextureUniform;
  private int cameraTextureId = -1;
  private boolean suppressTimestampZeroRendering = true;

  private int depthPositionAttrib;
  private int depthTexCoordAttrib;
  private int depthTextureUniform;
  private int depthTextureId = -1;

  public int getTextureId() {
    return cameraTextureId;
  }

  /**
   * Allocates and initializes OpenGL resources needed by the background renderer. Must be called on
   * the OpenGL thread, typically in {@link GLSurfaceView.Renderer#onSurfaceCreated(GL10,
   * EGLConfig)}.
   *
   * @param context Needed to access shader source.
   */
  public void createOnGlThread(Context context, int depthTextureId) throws IOException {
    // Generate the background texture.
    int[] textures = new int[1];
    GLES30.glGenTextures(1, textures, 0);
    cameraTextureId = textures[0];
    int textureTarget = GLES11Ext.GL_TEXTURE_EXTERNAL_OES;
    GLES30.glBindTexture(textureTarget, cameraTextureId);
    GLES30.glTexParameteri(textureTarget, GLES30.GL_TEXTURE_WRAP_S, GLES30.GL_CLAMP_TO_EDGE);
    GLES30.glTexParameteri(textureTarget, GLES30.GL_TEXTURE_WRAP_T, GLES30.GL_CLAMP_TO_EDGE);
    GLES30.glTexParameteri(textureTarget, GLES30.GL_TEXTURE_MIN_FILTER, GLES30.GL_LINEAR);
    GLES30.glTexParameteri(textureTarget, GLES30.GL_TEXTURE_MAG_FILTER, GLES30.GL_LINEAR);

    int numVertices = 4;
    if (numVertices != QUAD_COORDS.length / COORDS_PER_VERTEX) {
      throw new RuntimeException("Unexpected number of vertices in BackgroundRenderer.");
    }

    ByteBuffer bbCoords = ByteBuffer.allocateDirect(QUAD_COORDS.length * FLOAT_SIZE);
    bbCoords.order(ByteOrder.nativeOrder());
    quadCoords = bbCoords.asFloatBuffer();
    quadCoords.put(QUAD_COORDS);
    quadCoords.position(0);

    ByteBuffer bbTexCoordsTransformed =
            ByteBuffer.allocateDirect(numVertices * TEXCOORDS_PER_VERTEX * FLOAT_SIZE);
    bbTexCoordsTransformed.order(ByteOrder.nativeOrder());
    quadTexCoords = bbTexCoordsTransformed.asFloatBuffer();

    // Load render camera feed shader.
    {
      int vertexShader =
              ShaderUtil.loadGLShader(TAG, context, GLES30.GL_VERTEX_SHADER, CAMERA_VERTEX_SHADER_NAME);
      int fragmentShader =
              ShaderUtil.loadGLShader(
                      TAG, context, GLES30.GL_FRAGMENT_SHADER, CAMERA_FRAGMENT_SHADER_NAME);

      cameraProgram = GLES30.glCreateProgram();
      GLES30.glAttachShader(cameraProgram, vertexShader);
      GLES30.glAttachShader(cameraProgram, fragmentShader);
      GLES30.glLinkProgram(cameraProgram);
      GLES30.glUseProgram(cameraProgram);
      cameraPositionAttrib = GLES30.glGetAttribLocation(cameraProgram, "a_Position");
      cameraTexCoordAttrib = GLES30.glGetAttribLocation(cameraProgram, "a_TexCoord");
      ShaderUtil.checkGLError(TAG, "Program creation");

      cameraTextureUniform = GLES30.glGetUniformLocation(cameraProgram, "sTexture");
      ShaderUtil.checkGLError(TAG, "Program parameters");
    }

    // Load render depth map shader.
    {
      int vertexShader =
              ShaderUtil.loadGLShader(
                      TAG, context, GLES30.GL_VERTEX_SHADER, DEPTH_VISUALIZER_VERTEX_SHADER_NAME);
      int fragmentShader =
              ShaderUtil.loadGLShader(
                      TAG, context, GLES30.GL_FRAGMENT_SHADER, DEPTH_VISUALIZER_FRAGMENT_SHADER_NAME);

      depthProgram = GLES30.glCreateProgram();
      GLES30.glAttachShader(depthProgram, vertexShader);
      GLES30.glAttachShader(depthProgram, fragmentShader);
      GLES30.glLinkProgram(depthProgram);
      GLES30.glUseProgram(depthProgram);
      depthPositionAttrib = GLES30.glGetAttribLocation(depthProgram, "a_Position");
      depthTexCoordAttrib = GLES30.glGetAttribLocation(depthProgram, "a_TexCoord");
      ShaderUtil.checkGLError(TAG, "Program creation");

      depthTextureUniform = GLES30.glGetUniformLocation(depthProgram, "u_DepthTexture");
      ShaderUtil.checkGLError(TAG, "Program parameters");
    }

    this.depthTextureId = depthTextureId;
  }

  public void createOnGlThread(Context context) throws IOException {
    createOnGlThread(context, /*depthTextureId=*/ -1);
  }

  public void suppressTimestampZeroRendering(boolean suppressTimestampZeroRendering) {
    this.suppressTimestampZeroRendering = suppressTimestampZeroRendering;
  }

  /**
   * Draws the AR background image. The image will be drawn such that virtual content rendered with
   * the matrices provided by {@link com.google.ar.core.Camera#getViewMatrix(float[], int)} and
   * {@link com.google.ar.core.Camera#getProjectionMatrix(float[], int, float, float)} will
   * accurately follow static physical objects. This must be called <b>before</b> drawing virtual
   * content.
   *
   * @param frame The current {@code Frame} as returned by {@link Session#update()}.
   * @param debugShowDepthMap Toggles whether to show the live camera feed or latest depth image.
   */
  public void draw(@NonNull Frame frame, boolean debugShowDepthMap) {
    // If display rotation changed (also includes view size change), we need to re-query the uv
    // coordinates for the screen rect, as they may have changed as well.
    if (frame.hasDisplayGeometryChanged()) {
      frame.transformCoordinates2d(
              Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
              quadCoords,
              Coordinates2d.TEXTURE_NORMALIZED,
              quadTexCoords);
      Log.d(TAG, "draw: !!!!!!!!! is changed !!!!!!!!!");
    }

    if (frame.getTimestamp() == 0 && suppressTimestampZeroRendering) {
      // Suppress rendering if the camera did not produce the first frame yet. This is to avoid
      // drawing possible leftover data from previous sessions if the texture is reused.
      return;
    }

    draw(debugShowDepthMap);
  }

  public void draw(@NonNull Frame frame) {
    draw(frame, /*debugShowDepthMap=*/ false);
  }

  /**
   * Draws the camera image using the currently configured {@link BackgroundRenderer#quadTexCoords}
   * image texture coordinates.
   *
   * <p>The image will be center cropped if the camera sensor aspect ratio does not match the screen
   * aspect ratio, which matches the cropping behavior of {@link
   * Frame#transformCoordinates2d(Coordinates2d, float[], Coordinates2d, float[])}.
   */
  public void draw(
          int imageWidth, int imageHeight, float screenAspectRatio, int cameraToDisplayRotation) {
    // Crop the camera image to fit the screen aspect ratio.
    float imageAspectRatio = (float) imageWidth / imageHeight;
    float croppedWidth;
    float croppedHeight;
    if (screenAspectRatio < imageAspectRatio) {
      croppedWidth = imageHeight * screenAspectRatio;
      croppedHeight = imageHeight;
    } else {
      croppedWidth = imageWidth;
      croppedHeight = imageWidth / screenAspectRatio;
    }

    float u = (imageWidth - croppedWidth) / imageWidth * 0.5f;
    float v = (imageHeight - croppedHeight) / imageHeight * 0.5f;

    float[] texCoordTransformed;
    switch (cameraToDisplayRotation) {
      case 90:
        texCoordTransformed = new float[] {1 - u, 1 - v, 1 - u, v, u, 1 - v, u, v};
        break;
      case 180:
        texCoordTransformed = new float[] {1 - u, v, u, v, 1 - u, 1 - v, u, 1 - v};
        break;
      case 270:
        texCoordTransformed = new float[] {u, v, u, 1 - v, 1 - u, v, 1 - u, 1 - v};
        break;
      case 0:
        texCoordTransformed = new float[] {u, 1 - v, 1 - u, 1 - v, u, v, 1 - u, v};
        break;
      default:
        throw new IllegalArgumentException("Unhandled rotation: " + cameraToDisplayRotation);
    }

    // Write image texture coordinates.
    quadTexCoords.position(0);
    quadTexCoords.put(texCoordTransformed);

    draw(/*debugShowDepthMap=*/ false);
  }

  /**
   * Draws the camera background image using the currently configured {@link
   * BackgroundRenderer#quadTexCoords} image texture coordinates.
   */
  private void draw(boolean debugShowDepthMap) {
    // Ensure position is rewound before use.
    quadTexCoords.position(0);

    // No need to test or write depth, the screen quad has arbitrary depth, and is expected
    // to be drawn first.
    GLES30.glDisable(GLES30.GL_DEPTH_TEST);
    GLES30.glDepthMask(false);

    GLES30.glActiveTexture(GLES30.GL_TEXTURE0);

    if (debugShowDepthMap) {
      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTextureId);
      GLES30.glUseProgram(depthProgram);
      GLES30.glUniform1i(depthTextureUniform, 0);

      // Set the vertex positions and texture coordinates.
      GLES30.glVertexAttribPointer(
              depthPositionAttrib, COORDS_PER_VERTEX, GLES30.GL_FLOAT, false, 0, quadCoords);
      GLES30.glVertexAttribPointer(
              depthTexCoordAttrib, TEXCOORDS_PER_VERTEX, GLES30.GL_FLOAT, false, 0, quadTexCoords);
      GLES30.glEnableVertexAttribArray(depthPositionAttrib);
      GLES30.glEnableVertexAttribArray(depthTexCoordAttrib);
    } else {
      GLES30.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, cameraTextureId);
      GLES30.glUseProgram(cameraProgram);
      GLES30.glUniform1i(cameraTextureUniform, 0);

      // Set the vertex positions and texture coordinates.
      GLES30.glVertexAttribPointer(
              cameraPositionAttrib, COORDS_PER_VERTEX, GLES30.GL_FLOAT, false, 0, quadCoords);
      GLES30.glVertexAttribPointer(
              cameraTexCoordAttrib, TEXCOORDS_PER_VERTEX, GLES30.GL_FLOAT, false, 0, quadTexCoords);
      GLES30.glEnableVertexAttribArray(cameraPositionAttrib);
      GLES30.glEnableVertexAttribArray(cameraTexCoordAttrib);
    }

    GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4);

    // Disable vertex arrays
    if (debugShowDepthMap) {
      GLES30.glDisableVertexAttribArray(depthPositionAttrib);
      GLES30.glDisableVertexAttribArray(depthTexCoordAttrib);
    } else {
      GLES30.glDisableVertexAttribArray(cameraPositionAttrib);
      GLES30.glDisableVertexAttribArray(cameraTexCoordAttrib);
    }

    // Restore the depth state for further drawing.
    GLES30.glDepthMask(true);
    GLES30.glEnable(GLES30.GL_DEPTH_TEST);

    ShaderUtil.checkGLError(TAG, "BackgroundRendererDraw");
  }

  /**
   * (-1, 1) ------- (1, 1)
   *   |    \           |
   *   |       \        |
   *   |          \     |
   *   |             \  |
   * (-1, -1) ------ (1, -1)
   * Ensure triangles are front-facing, to support glCullFace().
   * This quad will be drawn using GL_TRIANGLE_STRIP which draws two
   * triangles: v0->v1->v2, then v2->v1->v3.
   */
  private static final float[] QUAD_COORDS =
          new float[] {
                  -1.0f, -1.0f, +1.0f, -1.0f, -1.0f, +1.0f, +1.0f, +1.0f,
          };
}
