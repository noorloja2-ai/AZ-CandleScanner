package com.example.floatingcandlescanner;

import android.content.Context;
import android.content.SharedPreferences;
import android.provider.Settings;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Locale;

public final class LicenseManager {
    public static final String VERIFY_URL="https://az-learning-gateway.noorloja2.workers.dev/v1/license/verify";
    private static final String PREF="az_license_v1";
    private LicenseManager(){}

    public static String installId(Context c){
        SharedPreferences p=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);
        String id=p.getString("install_id","");
        if(!id.isEmpty())return id;
        try{
            String seed=Settings.Secure.getString(c.getContentResolver(),Settings.Secure.ANDROID_ID)
                    +"|"+c.getPackageName()+"|AZ";
            MessageDigest d=MessageDigest.getInstance("SHA-256");
            byte[] b=d.digest(seed.getBytes(StandardCharsets.UTF_8));
            StringBuilder s=new StringBuilder();
            for(int i=0;i<16;i++)s.append(String.format(Locale.US,"%02x",b[i]));
            id=s.toString();
        }catch(Exception e){id=java.util.UUID.randomUUID().toString();}
        p.edit().putString("install_id",id).apply(); return id;
    }
    public static boolean cachedValid(Context c){
        return c.getSharedPreferences(PREF,Context.MODE_PRIVATE).getLong("expires_at",0)>System.currentTimeMillis();
    }
    public static long expiresAt(Context c){
        return c.getSharedPreferences(PREF,Context.MODE_PRIVATE).getLong("expires_at",0);
    }
    public static void save(Context c,String key,long expiresAt){
        c.getSharedPreferences(PREF,Context.MODE_PRIVATE).edit()
                .putString("key",key).putLong("expires_at",expiresAt).apply();
    }
    public static String savedKey(Context c){
        return c.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString("key","");
    }
}
