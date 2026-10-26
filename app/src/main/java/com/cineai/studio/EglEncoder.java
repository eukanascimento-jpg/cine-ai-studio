package com.cineai.studio;

import android.graphics.Bitmap;
import android.opengl.EGL14;
import android.opengl.EGLConfig;
import android.opengl.EGLContext;
import android.opengl.EGLDisplay;
import android.opengl.EGLExt;
import android.opengl.EGLSurface;
import android.opengl.GLES20;
import android.opengl.GLUtils;
import android.view.Surface;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.FloatBuffer;

/** Transfere quadros Canvas para a Surface de codificação por OpenGL ES 2.0.
 * MediaCodec.createInputSurface() NÃO suporta Surface.lockCanvas().
 */
public final class EglEncoder implements AutoCloseable {
    private static final int EGL_RECORDABLE_ANDROID = 0x3142;
    private final EGLDisplay display;
    private final EGLContext context;
    private final EGLSurface eglSurface;
    private final int program;
    private final int texture;
    private final int positionAttribute;
    private final int uvAttribute;
    private final FloatBuffer quad;
    private final int width;
    private final int height;
    private boolean initializedTexture;

    public EglEncoder(Surface encoderInput, int width, int height) {
        this.width = width; this.height = height;
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
        if (display == EGL14.EGL_NO_DISPLAY) throw new IllegalStateException("Sem EGLDisplay");
        int[] version = new int[2];
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) throw new IllegalStateException("EGL init falhou");
        int[] attrs = {
            EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
            EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
            EGL_RECORDABLE_ANDROID, 1, EGL14.EGL_NONE
        };
        EGLConfig[] configs = new EGLConfig[1];
        int[] count = new int[1];
        if (!EGL14.eglChooseConfig(display, attrs, 0, configs, 0, 1, count, 0) || count[0] == 0)
            throw new IllegalStateException("EGL recordable indisponível");
        context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE}, 0);
        if (context == EGL14.EGL_NO_CONTEXT) throw new IllegalStateException("EGL context indisponível");
        eglSurface = EGL14.eglCreateWindowSurface(display, configs[0], encoderInput,
                new int[]{EGL14.EGL_NONE}, 0);
        if (eglSurface == EGL14.EGL_NO_SURFACE) throw new IllegalStateException("EGL surface indisponível");
        if (!EGL14.eglMakeCurrent(display, eglSurface, eglSurface, context))
            throw new IllegalStateException("EGL makeCurrent falhou");
        String vert = "attribute vec4 aPos; attribute vec2 aUv; varying vec2 vUv; " +
                "void main(){gl_Position=aPos;vUv=aUv;}";
        String frag = "precision mediump float; varying vec2 vUv; uniform sampler2D uTexture;" +
                "void main(){gl_FragColor=texture2D(uTexture,vUv);}";
        int vertex = shader(GLES20.GL_VERTEX_SHADER, vert);
        int fragment = shader(GLES20.GL_FRAGMENT_SHADER, frag);
        program = GLES20.glCreateProgram();
        GLES20.glAttachShader(program, vertex);
        GLES20.glAttachShader(program, fragment);
        GLES20.glLinkProgram(program);
        int[] ok = new int[1]; GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("GL link: " + GLES20.glGetProgramInfoLog(program));
        GLES20.glDeleteShader(vertex); GLES20.glDeleteShader(fragment);
        positionAttribute = GLES20.glGetAttribLocation(program, "aPos");
        uvAttribute = GLES20.glGetAttribLocation(program, "aUv");
        float[] coords = {-1,-1,0,1, 1,-1,1,1, -1,1,0,0, 1,1,1,0};
        quad = ByteBuffer.allocateDirect(coords.length * 4).order(ByteOrder.nativeOrder()).asFloatBuffer();
        quad.put(coords); quad.position(0);
        int[] tex = new int[1]; GLES20.glGenTextures(1, tex, 0);
        texture = tex[0];
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE);
        GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE);
    }

    private static int shader(int type, String code) {
        int id = GLES20.glCreateShader(type);
        GLES20.glShaderSource(id, code); GLES20.glCompileShader(id);
        int[] ok = new int[1]; GLES20.glGetShaderiv(id, GLES20.GL_COMPILE_STATUS, ok, 0);
        if (ok[0] == 0) throw new IllegalStateException("GL shader: " + GLES20.glGetShaderInfoLog(id));
        return id;
    }

    public void frame(Bitmap bitmap, long timeNanos) {
        GLES20.glViewport(0, 0, width, height);
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture);
        if (!initializedTexture) {
            GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0);
            initializedTexture = true;
        } else {
            GLUtils.texSubImage2D(GLES20.GL_TEXTURE_2D, 0, 0, 0, bitmap);
        }
        GLES20.glUseProgram(program);
        quad.position(0);
        GLES20.glVertexAttribPointer(positionAttribute, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(positionAttribute);
        quad.position(2);
        GLES20.glVertexAttribPointer(uvAttribute, 2, GLES20.GL_FLOAT, false, 16, quad);
        GLES20.glEnableVertexAttribArray(uvAttribute);
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0);
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4);
        EGLExt.eglPresentationTimeANDROID(display, eglSurface, timeNanos);
        if (!EGL14.eglSwapBuffers(display, eglSurface)) throw new IllegalStateException("eglSwapBuffers falhou");
    }

    @Override public void close() {
        EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT);
        EGL14.eglDestroySurface(display, eglSurface);
        EGL14.eglDestroyContext(display, context);
        EGL14.eglReleaseThread();
        EGL14.eglTerminate(display);
    }
}
