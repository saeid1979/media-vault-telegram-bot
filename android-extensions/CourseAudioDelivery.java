package com.javid.downloader;

import android.content.Context;
import android.speech.tts.TextToSpeech;
import android.speech.tts.UtteranceProgressListener;

import org.json.JSONObject;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Uses the Android TTS engine with explicit es-ES or en-US pronunciation.
 * Creates a WAV file, uploads it as a Telegram AUDIO FILE (document, playable).
 * No external voice-generation API receives the learner's sentence.
 */
final class CourseAudioDelivery {
    private CourseAudioDelivery() {}
    static void send(Context context,long chat,String token,String language,String sentence)
        throws Exception {
        if(sentence==null||sentence.trim().isEmpty())return;
        if(!language.equals("es")&&!language.equals("en"))throw new IllegalArgumentException("Unknown language");
        File output=new File(context.getCacheDir(),"cefr_"+chat+"_"+UUID.randomUUID()+".wav");
        final CountDownLatch initialized=new CountDownLatch(1);
        final AtomicInteger status=new AtomicInteger(TextToSpeech.ERROR);
        TextToSpeech tts=new TextToSpeech(context.getApplicationContext(), code -> {
            status.set(code);
            initialized.countDown();
        });
        try{
            if(!initialized.await(12,TimeUnit.SECONDS)||status.get()!=TextToSpeech.SUCCESS)
                throw new Exception("موتور تبدیل متن به گفتار گوشی فعال نیست.");
            Locale voice=language.equals("es")?new Locale("es","ES"):Locale.US;
            int available=tts.setLanguage(voice);
            if(available==TextToSpeech.LANG_MISSING_DATA||
                available==TextToSpeech.LANG_NOT_SUPPORTED)
                throw new Exception("صدای "+(language.equals("es")?"اسپانیایی اسپانیا":"انگلیسی آمریکا")+
                    " روی گوشی میزبان بات نصب نیست.");
            tts.setSpeechRate(0.86f);
            final CountDownLatch created=new CountDownLatch(1);
            final AtomicBoolean ok=new AtomicBoolean(false);
            final String utterance="javid_cefr_"+UUID.randomUUID();
            tts.setOnUtteranceProgressListener(new UtteranceProgressListener(){
                @Override public void onStart(String id){}
                @Override public void onDone(String id){
                    if(utterance.equals(id)){ok.set(true);created.countDown();}
                }
                @Override public void onError(String id){if(utterance.equals(id))created.countDown();}
                @Override public void onError(String id,int error){if(utterance.equals(id))created.countDown();}
            });
            if(tts.synthesizeToFile(sentence,null,output,utterance)!=TextToSpeech.SUCCESS)
                throw new Exception("تولید فایل صوتی انجام نشد.");
            if(!created.await(35,TimeUnit.SECONDS)||!ok.get()||output.length()<44)
                throw new Exception("تولید صوت ناموفق بود.");
            if(output.length()>9L*1024*1024)
                throw new Exception("حجم فایل صوتی بیش از حد مجاز شد.");
            upload(chat,token,output,language);
        }finally{
            tts.stop();
            tts.shutdown();
            output.delete();
        }
    }

    private static void upload(long chat,String token,File wav,String language)throws Exception{
        String boundary="JavidLesson"+UUID.randomUUID().toString().replace("-","");
        HttpURLConnection connection=(HttpURLConnection)new URL(
            "https://api.telegram.org/bot"+token+"/sendDocument").openConnection();
        connection.setRequestMethod("POST");
        connection.setConnectTimeout(15000);
        connection.setReadTimeout(65000);
        connection.setDoOutput(true);
        connection.setChunkedStreamingMode(16*1024);
        connection.setRequestProperty("Content-Type","multipart/form-data; boundary="+boundary);
        try{
            String filename=language.equals("es")?"spanish_ES_lesson.wav":"english_US_lesson.wav";
            String caption=language.equals("es")?"🎧 تلفظ اسپانیایی رایج در اسپانیا":"🎧 تلفظ انگلیسی آمریکایی";
            try(OutputStream out=new BufferedOutputStream(connection.getOutputStream())){
                String prefix="--"+boundary+"\r\nContent-Disposition: form-data; name=\"chat_id\"\r\n\r\n"+chat+"\r\n"+
                    "--"+boundary+"\r\nContent-Disposition: form-data; name=\"caption\"\r\n\r\n"+caption+"\r\n"+
                    "--"+boundary+"\r\nContent-Disposition: form-data; name=\"document\"; filename=\""+filename+"\"\r\n"+
                    "Content-Type: audio/wav\r\n\r\n";
                out.write(prefix.getBytes(StandardCharsets.UTF_8));
                try(InputStream in=new BufferedInputStream(new FileInputStream(wav))){
                    byte[] chunk=new byte[16384];int n;
                    while((n=in.read(chunk))>=0)out.write(chunk,0,n);
                }
                out.write(("\r\n--"+boundary+"--\r\n").getBytes(StandardCharsets.UTF_8));
            }
            int code=connection.getResponseCode();
            try(InputStream in=(code>=200&&code<300)?connection.getInputStream():connection.getErrorStream()){
                if(in==null)throw new Exception("Telegram audio upload HTTP "+code);
                byte[] data=new byte[16384];
                int size=0,n;
                java.io.ByteArrayOutputStream buffer=new java.io.ByteArrayOutputStream();
                while((n=in.read(data))!=-1){
                    size+=n;if(size>200000)break;buffer.write(data,0,n);
                }
                if(code<200||code>=300||!new JSONObject(buffer.toString("UTF-8")).optBoolean("ok"))
                    throw new Exception("ارسال فایل صوتی به تلگرام ناموفق بود.");
            }
        }finally{connection.disconnect();}
    }
}
