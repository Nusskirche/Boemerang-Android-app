package nl.boemerang.editor;

import android.content.Context;
import android.graphics.Bitmap;
import android.media.*;
import android.net.Uri;
import android.opengl.*;
import android.view.Surface;
import java.io.*;
import java.nio.*;
import static android.opengl.GLES20.*;

final class Movie {
    interface Progress { void at(int frames); }
    static final int FPS = 30, FRAMES = 300;
    static void render(Context ctx, Uri uri, int startMs, File target, Progress progress) throws Exception {
        MediaMetadataRetriever reader = new MediaMetadataRetriever();
        MediaCodec codec = null;
        MediaMuxer mux = null;
        Surface surface = null;
        Painter painter = new Painter();
        try {
            reader.setDataSource(ctx, uri);
            int w = number(reader, MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH);
            int h = number(reader, MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT);
            if (number(reader, MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION) % 180 != 0) {
                int swap = w; w = h; h = swap;
            }
            double scale = Math.min(1, Math.min(1920.0 / Math.max(w,h),1080.0 / Math.min(w,h)));
            w = Math.max(2, (int)Math.round(w * scale / 2) * 2);
            h = Math.max(2, (int)Math.round(h * scale / 2) * 2);
            MediaFormat format = MediaFormat.createVideoFormat("video/avc",w,h);
            format.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
            format.setInteger(MediaFormat.KEY_BIT_RATE,Math.max(1000000,w*h*4));
            format.setInteger(MediaFormat.KEY_FRAME_RATE,FPS);
            format.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,1);
            codec = MediaCodec.createEncoderByType("video/avc");
            codec.configure(format,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
            surface = codec.createInputSurface();
            painter.open(surface,w,h);
            codec.start();
            mux = new MediaMuxer(target.getPath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int frame = 0, track = -1;
            long lastChange = android.os.SystemClock.elapsedRealtime();
            while (true) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                int out = codec.dequeueOutputBuffer(info,frame > FRAMES ? 10000 : 0);
                if (out == MediaCodec.INFO_OUTPUT_FORMAT_CHANGED) {
                    track = mux.addTrack(codec.getOutputFormat()); mux.start();
                } else if (out >= 0) {
                    if (info.size > 0 && (info.flags & MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0)
                        mux.writeSampleData(track,codec.getOutputBuffer(out),info);
                    codec.releaseOutputBuffer(out,false);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0) break;
                    lastChange = android.os.SystemClock.elapsedRealtime();
                } else if (frame < FRAMES) {
                    int step = frame <= FRAMES/2 ? frame : FRAMES-1-frame;
                    long time = 1000L*startMs + step * 4966666L / (frame <= FRAMES/2 ? 150 : 149);
                    Bitmap bmp = reader.getScaledFrameAtTime(time,MediaMetadataRetriever.OPTION_CLOSEST,w,h);
                    if (bmp == null) throw new IOException("Een videobeeld kon niet worden gelezen.");
                    try { painter.draw(bmp,frame*1000000000L/FPS); } finally { bmp.recycle(); }
                    progress.at(++frame);
                    lastChange = android.os.SystemClock.elapsedRealtime();
                } else if (frame == FRAMES) {
                    codec.signalEndOfInputStream(); frame++;
                } else if (android.os.SystemClock.elapsedRealtime()-lastChange > 30000) {
                    throw new IOException("De video-encoder reageert niet.");
                }
            }
            mux.stop();
        } finally {
            painter.close();
            if (surface != null) surface.release();
            if (codec != null) codec.release();
            if (mux != null) mux.release();
            reader.release();
        }
    }
    static int number(MediaMetadataRetriever r,int key) {
        String value = r.extractMetadata(key);
        return value == null ? 0 : Integer.parseInt(value);
    }
    static class Painter {
        EGLDisplay display = EGL14.EGL_NO_DISPLAY;
        EGLContext context = EGL14.EGL_NO_CONTEXT;
        EGLSurface window = EGL14.EGL_NO_SURFACE;
        int program,texture;
        static int shader(int type,String source) {
            int s = glCreateShader(type); glShaderSource(s,source); glCompileShader(s);
            int[] ok = new int[1]; glGetShaderiv(s,GL_COMPILE_STATUS,ok,0);
            if (ok[0]==0) throw new IllegalStateException(glGetShaderInfoLog(s));
            return s;
        }
        FloatBuffer quad = ByteBuffer.allocateDirect(32).order(ByteOrder.nativeOrder()).asFloatBuffer();
        void open(Surface output,int w,int h) {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY);
            int[] n = new int[1];
            EGL14.eglInitialize(display,n,0,n,0);
            EGLConfig[] configs = new EGLConfig[1];
            int[] attributes = {EGL14.EGL_RED_SIZE,8,EGL14.EGL_GREEN_SIZE,8,EGL14.EGL_BLUE_SIZE,8,EGL14.EGL_RENDERABLE_TYPE,4,EGL14.EGL_SURFACE_TYPE,EGL14.EGL_WINDOW_BIT,0x3142,1,EGL14.EGL_NONE};
            if (!EGL14.eglChooseConfig(display,attributes,0,configs,0,1,n,0) || n[0]==0)
                throw new IllegalStateException("Geen geschikte beeldverwerker gevonden.");
            context = EGL14.eglCreateContext(display,configs[0],EGL14.EGL_NO_CONTEXT,new int[]{EGL14.EGL_CONTEXT_CLIENT_VERSION,2,EGL14.EGL_NONE},0);
            window = EGL14.eglCreateWindowSurface(display,configs[0],output,new int[]{EGL14.EGL_NONE},0);
            if (!EGL14.eglMakeCurrent(display,window,window,context)) throw new IllegalStateException("Videobuffer niet beschikbaar.");
            program = glCreateProgram();
            glAttachShader(program,shader(GL_VERTEX_SHADER,"attribute vec2 p; varying vec2 uv; void main(){gl_Position=vec4(p,0.,1.); uv=vec2(p.x,-p.y)*0.5+0.5;}"));
            glAttachShader(program,shader(GL_FRAGMENT_SHADER,"precision mediump float; varying vec2 uv; uniform sampler2D tex; void main(){gl_FragColor=texture2D(tex,uv);}"));
            glLinkProgram(program); glUseProgram(program);
            quad.put(new float[]{-1,-1,1,-1,-1,1,1,1}).position(0);
            int p = glGetAttribLocation(program,"p"); glVertexAttribPointer(p,2,GL_FLOAT,false,0,quad); glEnableVertexAttribArray(p);
            int[] id = new int[1]; glGenTextures(1,id,0); texture=id[0]; glBindTexture(GL_TEXTURE_2D,texture);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MIN_FILTER,GL_LINEAR); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_MAG_FILTER,GL_LINEAR);
            glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_S,GL_CLAMP_TO_EDGE); glTexParameteri(GL_TEXTURE_2D,GL_TEXTURE_WRAP_T,GL_CLAMP_TO_EDGE);
            glViewport(0,0,w,h);
        }
        void draw(Bitmap bmp,long nanos) {
            GLUtils.texImage2D(GL_TEXTURE_2D,0,bmp,0);
            glDrawArrays(GL_TRIANGLE_STRIP,0,4);
            if(glGetError()!=GL_NO_ERROR || !EGLExt.eglPresentationTimeANDROID(display,window,nanos) || !EGL14.eglSwapBuffers(display,window))
                throw new IllegalStateException("Schrijven van het videobeeld is mislukt.");
        }
        void close() {
            if(display==EGL14.EGL_NO_DISPLAY) return;
            EGL14.eglMakeCurrent(display,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_SURFACE,EGL14.EGL_NO_CONTEXT);
            if(window!=EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display,window);
            if(context!=EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display,context);
            EGL14.eglReleaseThread(); EGL14.eglTerminate(display);
        }
    }
}
