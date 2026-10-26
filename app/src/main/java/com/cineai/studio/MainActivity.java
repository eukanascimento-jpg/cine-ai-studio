package com.cineai.studio;
import android.app.*;
import android.os.*;
import android.graphics.*;
import android.media.*;
import android.view.*;
import android.widget.*;
import java.io.*;
import java.nio.*;

public class MainActivity extends Activity {
  EditText story; TextView status; Spinner length; Button go;
  public void onCreate(Bundle b){
    super.onCreate(b);
    LinearLayout layout=new LinearLayout(this); layout.setOrientation(1); layout.setPadding(24,24,24,24); layout.setBackgroundColor(0xff101829);
    TextView h=new TextView(this); h.setText("CINE AI Studio - Offline"); h.setTextSize(26); h.setTextColor(-1); layout.addView(h);
    TextView info=new TextView(this); info.setText("Animação 2D local • sem IA generativa • sem créditos • exportação silenciosa em MP4."); info.setTextColor(0xffaabbcc);layout.addView(info);
    story=new EditText(this);story.setText("Uma raposa laranja encontra uma estrela brilhante na floresta.");story.setTextColor(-1);story.setMinLines(4);layout.addView(story);
    length=new Spinner(this);length.setAdapter(new ArrayAdapter<String>(this,android.R.layout.simple_spinner_dropdown_item,new String[]{"2 minutos","3 minutos","5 minutos"}));layout.addView(length);
    go=new Button(this);go.setText("GERAR VIDEO");layout.addView(go);
    status=new TextView(this);status.setTextColor(-1);status.setText("480p, 15 fps. O processamento pode aquecer o celular.");layout.addView(status);
    setContentView(layout);
    go.setOnClickListener(v->{
      go.setEnabled(false);int seconds=new int[]{120,180,300}[length.getSelectedItemPosition()];String text=story.getText().toString();
      new Thread(()->{try{File f=new File(getExternalFilesDir(null),"cine_"+System.currentTimeMillis()+".mp4");render(f,text,seconds);runOnUiThread(()->{status.setText("Criado: "+f.getAbsolutePath());go.setEnabled(true);});}catch(Exception e){runOnUiThread(()->{status.setText("Erro: "+e.getMessage());go.setEnabled(true);});}}).start();
    });
  }
  void scene(Canvas c,String text,float t,int duration){
    int w=640,h=360; c.drawColor(0xff19244f);Paint p=new Paint(3);
    for(int i=0;i<45;i++){p.setColor(0x99ffffff);c.drawCircle((i*107)%w,(i*73)%210,1+i%3,p);}
    p.setColor(0xff143f38);c.drawRect(0,274,w,360,p);
    for(int i=0;i<7;i++){float x=(i*117-(t*9)%117);p.setColor(0xff284639);c.drawRect(x,190,x+16,290,p);p.setColor(0xff21614b);c.drawCircle(x+8,188,34,p);}
    float x=130+(float)Math.sin(t*.27)*85,y=261+(float)Math.sin(t*5)*6;
    p.setColor(0xffec983f);c.drawOval(x-40,y-35,x+40,y+25,p);
    Path ear=new Path();ear.moveTo(x-27,y-22);ear.lineTo(x-20,y-62);ear.lineTo(x,y-26);ear.close();c.drawPath(ear,p);
    p.setColor(0xffffffff);c.drawOval(x+4,y-2,x+24,y+19,p);
    p.setColor(0xff101010);c.drawCircle(x+20,y-6,4,p);
    p.setColor(0xffffe38c);float sx=520+(float)Math.sin(t*.6)*45,sy=95+(float)Math.cos(t*.8)*25;
    for(int i=0;i<5;i++){float a=(float)(i*Math.PI*2/5);c.drawCircle(sx+(float)Math.cos(a)*14,sy+(float)Math.sin(a)*14,10,p);}
    p.setTypeface(Typeface.DEFAULT_BOLD);p.setTextSize(21);p.setColor(0xffffffff);
    String s=text.length()>49?text.substring(0,49)+"...":text;
    c.drawText(s,16,35,p);p.setTextSize(18);c.drawText("Cena "+(1+(int)t/12)+" | "+(int)t+" / "+duration+" s",18,341,p);
  }
  void render(File output,String prompt,int duration)throws Exception{
    int fps=15,w=640,h=360; MediaFormat fmt=MediaFormat.createVideoFormat("video/avc",w,h);
    fmt.setInteger(MediaFormat.KEY_COLOR_FORMAT,MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface);
    fmt.setInteger(MediaFormat.KEY_BIT_RATE,900000);fmt.setInteger(MediaFormat.KEY_FRAME_RATE,fps);fmt.setInteger(MediaFormat.KEY_I_FRAME_INTERVAL,2);
    MediaCodec codec=MediaCodec.createEncoderByType("video/avc");codec.configure(fmt,null,null,MediaCodec.CONFIGURE_FLAG_ENCODE);
    Surface surface=codec.createInputSurface();codec.start();MediaMuxer mux=new MediaMuxer(output.getAbsolutePath(),MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4);
    MediaCodec.BufferInfo info=new MediaCodec.BufferInfo();int track=-1;boolean started=false;
    Bitmap frameBitmap = Bitmap.createBitmap(w,h,Bitmap.Config.ARGB_8888);
    Canvas frameCanvas = new Canvas(frameBitmap);
    EglEncoder egl = new EglEncoder(surface,w,h);
    try{
      for(int frame=0;frame<duration*fps;frame++){
        scene(frameCanvas,prompt,frame/(float)fps,duration);egl.frame(frameBitmap,((long)frame*1000000000L)/fps);
        for(int attempts=0;attempts<5;attempts++){
          int idx=codec.dequeueOutputBuffer(info,10000);
          if(idx==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED){track=mux.addTrack(codec.getOutputFormat());mux.start();started=true;}
          else if(idx>=0){ByteBuffer buf=codec.getOutputBuffer(idx);
            if(started && info.size>0 && (info.flags&MediaCodec.BUFFER_FLAG_CODEC_CONFIG)==0){buf.position(info.offset);buf.limit(info.offset+info.size);mux.writeSampleData(track,buf,info);}
            codec.releaseOutputBuffer(idx,false);
          }else break;
        }
        if(frame%150==0){final int progress=frame*100/(duration*fps);runOnUiThread(()->status.setText("Gerando: "+progress+"%"));}
      }
      codec.signalEndOfInputStream();boolean eof=false;
      while(!eof){int idx=codec.dequeueOutputBuffer(info,100000);
        if(idx==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED&&!started){track=mux.addTrack(codec.getOutputFormat());mux.start();started=true;}
        else if(idx>=0){ByteBuffer buf=codec.getOutputBuffer(idx);if(started&&info.size>0){buf.position(info.offset);buf.limit(info.offset+info.size);mux.writeSampleData(track,buf,info);}eof=(info.flags&MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0;codec.releaseOutputBuffer(idx,false);}
      }
    }finally{egl.close();frameBitmap.recycle();codec.stop();codec.release();surface.release();if(started)mux.stop();mux.release();}
  }
}