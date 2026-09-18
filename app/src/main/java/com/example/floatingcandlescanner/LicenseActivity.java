package com.example.floatingcandlescanner;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.net.Uri;
import android.os.*;
import android.view.Gravity;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class LicenseActivity extends Activity {
    EditText key; TextView status,code; Button activate;
    final Handler main=new Handler(Looper.getMainLooper());

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        if(LicenseManager.cachedValid(this)){openApp();return;}
        LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(22),dp(26),dp(22),dp(22));r.setGravity(Gravity.CENTER_HORIZONTAL);
        r.setBackgroundColor(Color.rgb(2,6,23));
        TextView title=t("AZ LICENCE ACTIVATION",24,Color.rgb(34,211,238));title.setTypeface(null,Typeface.BOLD);r.addView(title);
        r.addView(t("A valid 1–12 month licence is required to use AZ.",14,Color.WHITE),lp(12));
        code=t("INSTALLATION CODE\n"+LicenseManager.installId(this),12,Color.rgb(167,243,208));
        code.setTextIsSelectable(true);code.setPadding(dp(12),dp(12),dp(12),dp(12));r.addView(code,lp(14));
        Button email=button("REQUEST BY EMAIL");email.setOnClickListener(v->requestEmail());r.addView(email,lp(8));
        Button whatsapp=button("REQUEST BY WHATSAPP");whatsapp.setOnClickListener(v->requestWhatsApp());r.addView(whatsapp,lp(16));
        r.addView(t("Contact: anamul00pt@gmail.com  •  WhatsApp +351 920 484 291",12,Color.LTGRAY),lp(14));
        key=new EditText(this);key.setHint("Enter licence key");key.setTextColor(Color.WHITE);key.setHintTextColor(Color.GRAY);
        key.setSingleLine(true);key.setText(LicenseManager.savedKey(this));r.addView(key,lp(8));
        activate=button("ACTIVATE LICENCE");activate.setOnClickListener(v->verify());r.addView(activate,lp(10));
        status=t("Internet connection is required for activation.",13,Color.LTGRAY);r.addView(status);
        ScrollView s=new ScrollView(this);s.addView(r);setContentView(s);
    }
    void requestEmail(){
        String body="Hello, I want an AZ licence.\n\nInstallation code: "+LicenseManager.installId(this)+"\nRequested duration: ___ month(s)";
        Intent i=new Intent(Intent.ACTION_SENDTO,Uri.parse("mailto:anamul00pt@gmail.com?subject="+Uri.encode("AZ licence request")+"&body="+Uri.encode(body)));
        try{startActivity(i);}catch(Exception e){status.setText("No email app found. Email anamul00pt@gmail.com");}
    }
    void requestWhatsApp(){
        String msg="Hello, I want an AZ licence.%0A%0AInstallation code: "+Uri.encode(LicenseManager.installId(this))+"%0ARequested duration: ___ month(s)";
        try{startActivity(new Intent(Intent.ACTION_VIEW,Uri.parse("https://wa.me/351920484291?text="+msg)));}
        catch(Exception e){status.setText("Open WhatsApp and contact +351 920 484 291");}
    }
    void verify(){
        String k=key.getText().toString().trim().toUpperCase();
        if(!k.matches("AZ-[A-Z0-9]{4}(-[A-Z0-9]{4}){3}")){status.setText("Enter a valid AZ licence key.");return;}
        activate.setEnabled(false);status.setText("Checking licence…");
        new Thread(()->{
            HttpURLConnection c=null;
            try{
                c=(HttpURLConnection)new URL(LicenseManager.VERIFY_URL).openConnection();
                c.setConnectTimeout(12000);c.setReadTimeout(12000);c.setRequestMethod("POST");c.setDoOutput(true);
                c.setRequestProperty("Content-Type","application/json");
                JSONObject q=new JSONObject();q.put("key",k);q.put("install_id",LicenseManager.installId(this));
                try(OutputStream o=c.getOutputStream()){o.write(q.toString().getBytes(StandardCharsets.UTF_8));}
                int http=c.getResponseCode();InputStream in=http<400?c.getInputStream():c.getErrorStream();
                String body=read(in);JSONObject out=new JSONObject(body);
                if(http==200&&out.optBoolean("valid")){
                    long expires=out.getLong("expires_at");LicenseManager.save(this,k,expires);
                    main.post(this::openApp);
                }else{
                    String e=out.optString("error","not_valid").replace('_',' ');
                    main.post(()->{activate.setEnabled(true);status.setText("Licence not accepted: "+e);});
                }
            }catch(Exception e){main.post(()->{activate.setEnabled(true);status.setText("Could not contact licence server. Check internet and try again.");});}
            finally{if(c!=null)c.disconnect();}
        }).start();
    }
    String read(InputStream in)throws IOException{if(in==null)return "{}";BufferedReader b=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();String x;while((x=b.readLine())!=null)s.append(x);return s.toString();}
    void openApp(){startActivity(new Intent(this,MainActivity.class));finish();}
    TextView t(String x,int sp,int color){TextView v=new TextView(this);v.setText(x);v.setTextSize(sp);v.setTextColor(color);v.setGravity(Gravity.CENTER);return v;}
    Button button(String x){Button b=new Button(this);b.setText(x);b.setAllCaps(false);b.setTypeface(null,Typeface.BOLD);return b;}
    LinearLayout.LayoutParams lp(int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(bottom));return p;}
    int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
