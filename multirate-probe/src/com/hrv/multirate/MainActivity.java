package com.hrv.multirate;

import android.app.Activity;
import android.content.Context;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.PowerManager;
import android.util.Log;

import com.huami.watch.klvp.KlvpStream;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

public class MainActivity extends Activity {
    static final String TAG="MultiPPG";
    static class Sample { long t; int stream; float v; Sample(long t,int s,float v){this.t=t;this.stream=s;this.v=v;} }
    static class Stream { String name; int n; long first,last; float min=Float.MAX_VALUE,max=-Float.MAX_VALUE; }
    @Override public void onCreate(Bundle b){ super.onCreate(b); new Thread(new Runnable(){public void run(){probe();finish();}}).start(); }
    void probe(){
        PowerManager.WakeLock wl=((PowerManager)getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK,"multi"); wl.acquire();
        try { KlvpStream.sendRequestToSensorHub('a',(short)0,(byte)0,(byte)1,(byte)0,(short)4,new byte[]{(byte)0xd0,2,1}); } catch(Throwable x){Log.i(TAG,"KLVP "+x);}
        try{Thread.sleep(3000);}catch(Exception e){}
        SensorManager sm=(SensorManager)getSystemService(Context.SENSOR_SERVICE); List<Sensor> ps=sm.getSensorList(65538);
        final List<Sample> all=Collections.synchronizedList(new ArrayList<Sample>()); final Stream[] st=new Stream[ps.size()]; final SensorEventListener[] ls=new SensorEventListener[ps.size()];
        HandlerThread ht=new HandlerThread("multi");ht.start(); Handler h=new Handler(ht.getLooper());
        Log.i(TAG,"streams="+ps.size());
        for(int i=0;i<ps.size();i++){ final int id=i; st[i]=new Stream();st[i].name=ps.get(i).getName(); ls[i]=new SensorEventListener(){public void onSensorChanged(SensorEvent e){long t=System.nanoTime();Stream s=st[id];if(s.n==0)s.first=t;s.last=t;s.n++;float v=e.values[0];if(v<s.min)s.min=v;if(v>s.max)s.max=v;all.add(new Sample(t,id,v));}public void onAccuracyChanged(Sensor s,int a){}}; boolean ok=sm.registerListener(ls[i],ps.get(i),2000,h);Log.i(TAG,"register["+i+"] "+st[i].name+"="+ok);}
        try{Thread.sleep(25000);}catch(Exception e){}
        for(int i=0;i<ps.size();i++)sm.unregisterListener(ls[i]);ht.quitSafely();
        for(int i=0;i<st.length;i++){Stream s=st[i];double rate=s.n>1?(s.n-1)/((s.last-s.first)/1e9):0;Log.i(TAG,String.format("stream[%d] %s n=%d rate=%.2fHz range=%.0f..%.0f",i,s.name,s.n,rate,s.min,s.max));}
        Collections.sort(all,new Comparator<Sample>(){public int compare(Sample a,Sample b){return a.t<b.t?-1:(a.t>b.t?1:0);}});
        if(all.size()>1){double span=(all.get(all.size()-1).t-all.get(0).t)/1e9;double combined=(all.size()-1)/span;long[] gaps=new long[all.size()-1];for(int i=1;i<all.size();i++)gaps[i-1]=all.get(i).t-all.get(i-1).t;java.util.Arrays.sort(gaps);Log.i(TAG,String.format("combined n=%d rate=%.2fHz medianGap=%.3fms p90Gap=%.3fms",all.size(),combined,gaps[gaps.length/2]/1e6,gaps[(int)(gaps.length*.9)]/1e6));StringBuilder seq=new StringBuilder("first80 streams=");for(int i=0;i<Math.min(80,all.size());i++)seq.append(all.get(i).stream);Log.i(TAG,seq.toString());}
        try { KlvpStream.sendRequestToSensorHub('a',(short)0,(byte)0,(byte)1,(byte)0,(short)4,new byte[]{(byte)0xd0,2,0}); } catch(Throwable x){}
        wl.release();Log.i(TAG,"DONE");
    }
}
