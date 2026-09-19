package com.example.azlicensegenerator;

import android.app.*;
import android.content.*;
import android.graphics.Color;
import android.graphics.Typeface;
import android.os.*;
import android.text.InputType;
import android.view.Gravity;
import android.widget.*;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;

public class MainActivity extends Activity {
    static final String URL="https://az-learning-gateway.noorloja2.workers.dev/v1/license/admin/create";
    EditText token; Spinner months; TextView result,status; Button generate,copy,share;
    Handler main=new Handler(Looper.getMainLooper());
    String lastKey="";

    @Override public void onCreate(Bundle b){
        super.onCreate(b);
        LinearLayout r=new LinearLayout(this);r.setOrientation(LinearLayout.VERTICAL);
        r.setPadding(dp(22),dp(30),dp(22),dp(24));r.setBackgroundColor(Color.rgb(2,6,23));
        TextView title=text("AZ LICENCE GENERATOR",23,Color.rgb(34,211,238));title.setTypeface(null,Typeface.BOLD);r.addView(title,lp(16));
        r.addView(text("Private administrator app • Generate 1–12 month keys",13,Color.LTGRAY),lp(18));
        token=new EditText(this);token.setHint("Cloudflare admin token");token.setSingleLine(true);
        token.setInputType(InputType.TYPE_CLASS_TEXT|InputType.TYPE_TEXT_VARIATION_PASSWORD);
        token.setTextColor(Color.WHITE);token.setHintTextColor(Color.GRAY);r.addView(token,lp(12));
        months=new Spinner(this);String[] items=new String[12];for(int i=0;i<12;i++)items[i]=(i+1)+" month"+(i==0?"":"s");
        months.setAdapter(new ArrayAdapter<>(this,android.R.layout.simple_spinner_dropdown_item,items));r.addView(months,lp(14));
        generate=button("GENERATE LICENCE KEY");generate.setOnClickListener(v->generate());r.addView(generate,lp(14));
        result=text("No key generated",18,Color.rgb(167,243,208));result.setTextIsSelectable(true);r.addView(result,lp(14));
        copy=button("COPY KEY");copy.setEnabled(false);copy.setOnClickListener(v->copy());r.addView(copy,lp(8));
        share=button("SHARE KEY");share.setEnabled(false);share.setOnClickListener(v->share());r.addView(share,lp(12));
        status=text("The private token is sent only to your Cloudflare Worker and is not saved.",12,Color.LTGRAY);r.addView(status);
        ScrollView s=new ScrollView(this);s.addView(r);setContentView(s);
    }
    void generate(){
        String t=token.getText().toString().trim();if(t.isEmpty()){status.setText("Enter your private admin token.");return;}
        int m=months.getSelectedItemPosition()+1;generate.setEnabled(false);status.setText("Generating secure key…");
        new Thread(()->{
            HttpURLConnection c=null;
            try{
                c=(HttpURLConnection)new URL(URL).openConnection();c.setConnectTimeout(12000);c.setReadTimeout(12000);
                c.setRequestMethod("POST");c.setDoOutput(true);c.setRequestProperty("Content-Type","application/json");
                c.setRequestProperty("Authorization","Bearer "+t);
                JSONObject q=new JSONObject();q.put("months",m);
                try(OutputStream o=c.getOutputStream()){o.write(q.toString().getBytes(StandardCharsets.UTF_8));}
                int code=c.getResponseCode();InputStream in=code<400?c.getInputStream():c.getErrorStream();
                JSONObject out=new JSONObject(read(in));
                if(code==200&&out.optBoolean("ok")){
                    String k=out.getString("key");main.post(()->showKey(k,m));
                }else{String e=out.optString("error","request failed").replace('_',' ');main.post(()->fail(e));}
            }catch(Exception e){main.post(()->fail("Licence server unavailable. Deploy Cloudflare first."));}
            finally{if(c!=null)c.disconnect();}
        }).start();
    }
    void showKey(String k,int m){lastKey=k;result.setText(k+"\n"+m+" month"+(m==1?"":"s"));copy.setEnabled(true);share.setEnabled(true);generate.setEnabled(true);status.setText("Key created. Send it to the user who requested a licence.");}
    void fail(String x){generate.setEnabled(true);status.setText("Error: "+x);}
    void copy(){((android.content.ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("AZ licence key",lastKey));Toast.makeText(this,"Key copied",Toast.LENGTH_SHORT).show();}
    void share(){Intent i=new Intent(Intent.ACTION_SEND);i.setType("text/plain");i.putExtra(Intent.EXTRA_TEXT,"Your AZ licence key: "+lastKey);startActivity(Intent.createChooser(i,"Send licence key"));}
    String read(InputStream in)throws Exception{if(in==null)return "{}";BufferedReader b=new BufferedReader(new InputStreamReader(in,StandardCharsets.UTF_8));StringBuilder s=new StringBuilder();String x;while((x=b.readLine())!=null)s.append(x);return s.toString();}
    TextView text(String x,int sp,int color){TextView v=new TextView(this);v.setText(x);v.setTextSize(sp);v.setTextColor(color);v.setGravity(Gravity.CENTER);return v;}
    Button button(String x){Button b=new Button(this);b.setText(x);b.setAllCaps(false);b.setTypeface(null,Typeface.BOLD);return b;}
    LinearLayout.LayoutParams lp(int bottom){LinearLayout.LayoutParams p=new LinearLayout.LayoutParams(-1,-2);p.setMargins(0,0,0,dp(bottom));return p;}
    int dp(int v){return Math.round(v*getResources().getDisplayMetrics().density);}
}
