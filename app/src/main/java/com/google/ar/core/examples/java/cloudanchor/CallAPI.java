package com.google.ar.core.examples.java.cloudanchor;
import android.app.Activity;
import android.os.AsyncTask;
import android.os.SystemClock;
import android.view.View;
import android.widget.ImageView;

import com.google.ar.core.examples.java.common.helpers.SnackbarHelper;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.BufferedOutputStream;
import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.net.HttpURLConnection;
import java.net.URL;

import javax.net.ssl.HttpsURLConnection;

public class CallAPI extends AsyncTask<String, String, String> {

    public SnackbarHelper snack;
    public String responseR;
    public Activity activity;
    public FinalLabel finalLabel;
    public ObjectType objType;

    public CallAPI(){
        //set context variables if required
    }
    @Override
    protected void onPostExecute(String s){
        snack.showMessageWithDismiss(activity,responseR);
    }
    @Override
    protected void onPreExecute() {
        super.onPreExecute();
    }


    @Override
    public String doInBackground(String... params) {
        String requestURL = params[0];
        String data = params[1];
        URL url;
        String response = "";
        try {
            url = new URL(requestURL);

            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setReadTimeout(15000);
            conn.setConnectTimeout(15000);
            conn.setRequestMethod("POST");
            conn.setDoInput(true);
            conn.setDoOutput(true);


            OutputStream os = conn.getOutputStream();
            BufferedWriter writer = new BufferedWriter(
                    new OutputStreamWriter(os, "UTF-8"));
            writer.write(data);

            writer.flush();
            writer.close();
            os.close();
            int responseCode=conn.getResponseCode();

            if (responseCode == HttpsURLConnection.HTTP_OK) {
                String line;
                BufferedReader br=new BufferedReader(new InputStreamReader(conn.getInputStream()));
                while ((line=br.readLine()) != null) {
                    response+=line;
                }
            }
            else {
                response="";

            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        responseR = response;

        try {
            JSONObject jsonObject = new JSONObject(responseR);
            //JSONArray array = jsonObject.getJSONArray("labels");
            //if(array.length()>0) {
                String finallLabel = (String) jsonObject.getString("labels");
                String singleResult = (String) jsonObject.getString("single");
                String imageType = (String) jsonObject.getString("type");
                if(!(finallLabel.equals("tv") || finallLabel.equals("cup") || finallLabel.equals("cell phone") || finallLabel.equals("keyboard") || finallLabel.equals("person") || finallLabel.equals("pens") || finallLabel.equals("shoes")))
                    finallLabel = "default";
                System.out.println(finallLabel);
                objType.type = finallLabel;
                finalLabel.label = finallLabel;
                StringBuilder sb = new StringBuilder();
                sb.append("FINAL RESULT:");
                sb.append(finallLabel);
                sb.append(System.getProperty("line.separator"));
                sb.append("SINGLE RESULT:");
                sb.append(singleResult);
                sb.append(System.getProperty("line.separator"));
                sb.append("IMAGE TYPE:");
                sb.append(imageType);
                snack.showMessageWithDismiss(activity,sb.toString());
            //}
        }catch(Exception e){e.printStackTrace();}
        return response;
    }
}