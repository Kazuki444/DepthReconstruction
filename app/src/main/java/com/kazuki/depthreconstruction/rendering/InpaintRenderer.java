package com.kazuki.depthreconstruction.rendering;

import android.content.Context;
import android.opengl.GLES30;
import android.util.Log;

import androidx.annotation.NonNull;

import com.google.ar.core.Coordinates2d;
import com.google.ar.core.Frame;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

public class InpaintRenderer {
  private static final String TAG = InpaintRenderer.class.getSimpleName();

  // Shader names
  private static final String INPAINT_VERTEX_SHADER_NAME = "shaders/inpaintquad.vert";
  private static final String INPAINT_FRAGMENT_SHADER_NAME = "shaders/inpaintquad.frag";

  private static final int COORDS_PER_VERTEX = 2;
  private static final int FLOAT_SIZE = 4;

  private static final float[] QUAD_COORDS =
          new float[]{
                  -0.6f, -0.4f, +0.6f, -0.4f, -0.6f, +0.4f, +0.6f, +0.4f
          };

  private FloatBuffer quadCoords;
  private FloatBuffer quadTexCoords;

  private int inpaintProgram;

  private int positionAttrib;
  private int texCoordAttrib;
  private int depthTextureUniform;
  private int depthTextureId = -1;

  private int slice = 5;

  private boolean isNeedTexCoordsTransformed = true;

  public void createOnGlThread(Context context, int depthTextureId) throws IOException {
    // 矩形を分割
    float[] SPLIT_QUAD_COODS = splitQuad(slice);

    ByteBuffer bbCoords = ByteBuffer.allocateDirect(SPLIT_QUAD_COODS.length * FLOAT_SIZE);
    bbCoords.order(ByteOrder.nativeOrder());
    quadCoords = bbCoords.asFloatBuffer();
    quadCoords.put(SPLIT_QUAD_COODS);
    quadCoords.position(0);

    ByteBuffer bbTexCoords = ByteBuffer.allocateDirect(SPLIT_QUAD_COODS.length * FLOAT_SIZE);
    bbTexCoords.order(ByteOrder.nativeOrder());
    quadTexCoords = bbTexCoords.asFloatBuffer();


    // load shader
    {
      int vertexShader =
              ShaderUtil.loadGLShader(TAG, context, GLES30.GL_VERTEX_SHADER, INPAINT_VERTEX_SHADER_NAME);
      int fragmentShader =
              ShaderUtil.loadGLShader(TAG, context, GLES30.GL_FRAGMENT_SHADER, INPAINT_FRAGMENT_SHADER_NAME);

      inpaintProgram = GLES30.glCreateProgram();
      GLES30.glAttachShader(inpaintProgram, vertexShader);
      GLES30.glAttachShader(inpaintProgram, fragmentShader);
      GLES30.glLinkProgram(inpaintProgram);
      GLES30.glUseProgram(inpaintProgram);
      positionAttrib = GLES30.glGetAttribLocation(inpaintProgram, "a_Position");
      texCoordAttrib = GLES30.glGetAttribLocation(inpaintProgram, "a_TexCoord");
      ShaderUtil.checkGLError(TAG, "Program creation");

      depthTextureUniform = GLES30.glGetUniformLocation(inpaintProgram, "u_DepthTexture");
      ShaderUtil.checkGLError(TAG, "Program creation");
    }

    this.depthTextureId = depthTextureId;
  }

  public void draw(@NonNull Frame frame, boolean debugShowDepthMap, boolean isInpaintModeChecked) {
    if (debugShowDepthMap && isInpaintModeChecked) {
      if (isNeedTexCoordsTransformed) {
        frame.transformCoordinates2d(
                Coordinates2d.OPENGL_NORMALIZED_DEVICE_COORDINATES,
                quadCoords,
                Coordinates2d.TEXTURE_NORMALIZED,
                quadTexCoords);
        isNeedTexCoordsTransformed = false;
        Log.d(TAG, "draw: !!!!!!!!! is changed !!!!!!!!!");
      }

      // Ensure position is rewound before use.
      quadTexCoords.position(0);

      GLES30.glDisable(GLES30.GL_DEPTH_TEST);
      GLES30.glDepthMask(false);

      GLES30.glBindTexture(GLES30.GL_TEXTURE_2D, depthTextureId);
      GLES30.glUseProgram(inpaintProgram);
      GLES30.glUniform1i(depthTextureUniform, 0);

      // Set the vertex positions
      GLES30.glVertexAttribPointer(
              positionAttrib, COORDS_PER_VERTEX, GLES30.GL_FLOAT, false, 0, quadCoords);
      GLES30.glVertexAttribPointer(
              texCoordAttrib, COORDS_PER_VERTEX, GLES30.GL_FLOAT, false, 0, quadTexCoords);
      GLES30.glEnableVertexAttribArray(positionAttrib);
      GLES30.glEnableVertexAttribArray(texCoordAttrib);

      // draw quad
      GLES30.glDrawArrays(GLES30.GL_TRIANGLE_STRIP, 0, 4 + slice * 2);

      // Disable vertex arrays
      GLES30.glDisableVertexAttribArray(positionAttrib);
      GLES30.glDisableVertexAttribArray(texCoordAttrib);

      GLES30.glDepthMask(true);
      GLES30.glEnable(GLES30.GL_DEPTH_TEST);

      ShaderUtil.checkGLError(TAG, "InpaintRendererDraw");
    }
  }

  // 矩形を分割する
  private float[] splitQuad(int slice) {
    if (slice == 0) {
      return QUAD_COORDS;
    }

    float[] quad_coords = QUAD_COORDS;
    int num = 4 * slice + 8;
    float[] splitQuadCoords = new float[num];

    // 四隅の座標を求める（左下・右下・左上・右上）
    splitQuadCoords[0] = quad_coords[0];
    splitQuadCoords[1] = quad_coords[1];
    splitQuadCoords[2] = quad_coords[2];
    splitQuadCoords[3] = quad_coords[3];
    splitQuadCoords[num - 4] = quad_coords[4];
    splitQuadCoords[num - 3] = quad_coords[5];
    splitQuadCoords[num - 2] = quad_coords[6];
    splitQuadCoords[num - 1] = quad_coords[7];

    // 中間の座標を求める
    float step = (quad_coords[5] - quad_coords[1]) / (slice + 1);
    for (int i = 1; i <= slice; i++) {
      splitQuadCoords[4 * i] = quad_coords[0];
      splitQuadCoords[4 * i + 1] = quad_coords[1] + i * step;
      splitQuadCoords[4 * i + 2] = quad_coords[2];
      splitQuadCoords[4 * i + 3] = quad_coords[3] + i * step;
    }

    return splitQuadCoords;
  }
}
