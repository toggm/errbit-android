/*
    Errbit Notifier for Android
    Copyright (c) 2012 Matteo Piotto <matteo.piotto@welaika.com>
    http://welaika.com
    
    based on
    
    Airbrake Notifier for Android
    Copyright (c) 2011 James Smith <james@loopj.com>
    http://loopj.com

    Licensed under the Apache License, Version 2.0 (the "License");
    you may not use this file except in compliance with the License.
    You may obtain a copy of the License at

        http://www.apache.org/licenses/LICENSE-2.0

    Unless required by applicable law or agreed to in writing, software
    distributed under the License is distributed on an "AS IS" BASIS,
    WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
    See the License for the specific language governing permissions and
    limitations under the License.
*/

package com.welaika.android.errbit;

import java.net.HttpURLConnection;
import java.net.URL;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileWriter;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.lang.Thread.UncaughtExceptionHandler;
import java.util.Map;
import java.util.Random;

import org.xmlpull.v1.XmlSerializer;

import android.content.Context;
import android.content.pm.PackageManager;
import android.content.pm.PackageInfo;
import android.net.ConnectivityManager;
import android.net.NetworkInfo;
import android.content.Context;
import android.os.AsyncTask;
import android.util.Log;
import android.util.Xml;

/**
 * ErrBit Notifier
 *
 * Logs exceptions to Errbit
 * 
 * Based on https://github.com/loopj/airbrake-android/ v.1.3.0  
 */
public class ErrbitNotifier {
    private static final String LOG_TAG = "ErrbitNotifier";

    private static final String AIRBRAKE_API_VERSION = "2.0";

    private static final String NOTIFIER_NAME = "Android Errbit Notifier";
    private static final String NOTIFIER_VERSION = "0.2.0";
    private static final String NOTIFIER_URL = "http://welaika.com";

    private static final String UNSENT_EXCEPTION_PATH = "/unsent_errbit_exceptions/";

    private static final String ENVIRONMENT_PRODUCTION = "production";
    private static final String ENVIRONMENT_DEFAULT = ENVIRONMENT_PRODUCTION;

    // Exception meta-data
    private static String environmentName = ENVIRONMENT_DEFAULT;
    private static String packageName = "unknown";
    private static String versionName = "unknown";
    private static String phoneModel = android.os.Build.MODEL;
    private static String androidVersion = android.os.Build.VERSION.RELEASE;
    private static String brandDevice = android.os.Build.BRAND;
    private static String manufacturerDevice = android.os.Build.MANUFACTURER;
    

    // Anything extra the app wants to add
    private static Map<String, String> extraData;

    // Errbit api key
    private static String apiKey;

    // Errbit api key
    private static String errbit_endpoint = "http://airbrakeapp.com/notifier_api/v2/notices";
    
    // Exception storage info
    private static boolean notifyOnlyProduction = false;
    private static String filePath;
    private static boolean diskStorageEnabled = false;

    // Wrapper class to send uncaught exceptions to errbit
    private static class ErrbitExceptionHandler implements UncaughtExceptionHandler {
        private UncaughtExceptionHandler defaultExceptionHandler;

        public ErrbitExceptionHandler(UncaughtExceptionHandler defaultExceptionHandlerIn) {
            defaultExceptionHandler = defaultExceptionHandlerIn;
        }

        public void uncaughtException(Thread t, Throwable e) {
            ErrbitNotifier.notify(e);
            defaultExceptionHandler.uncaughtException(t, e);
        }
    }

    // Register to send exceptions to airbrake
    public static void register(Context context, String endpoint, String apiKey) {
        register(context, endpoint, apiKey, ENVIRONMENT_DEFAULT, true);
    }

    public static void register(Context context, String endpoint, String apiKey, String environmentName) {
        register(context, endpoint, apiKey, environmentName, true);
    }

    public static void register(Context context, String endpoint, String apiKey, String environmentName, boolean notifyOnlyProduction) {
        // Require an airbrake api key
        if(apiKey != null) {
            ErrbitNotifier.apiKey = apiKey;
        } else {
            throw new RuntimeException("ErrBitNotifier requires an API key.");
        }
        
        if(endpoint != null) {
            ErrbitNotifier.errbit_endpoint = "http://" + endpoint + "/notifier_api/v2/notices";
        } else {
            ErrbitNotifier.errbit_endpoint = "http://airbrakeapp.com/notifier_api/v2/notices";
        }

        // Checked if context is passed
        if(context == null) {
            throw new IllegalArgumentException("context cannot be null.");
        }
        
        // Fill in environment name if passed
        if(environmentName != null) {
            ErrbitNotifier.environmentName = environmentName;
        }

        // Check which exception types to notify
        ErrbitNotifier.notifyOnlyProduction = notifyOnlyProduction;

        // Connect our default exception handler
        UncaughtExceptionHandler currentHandler = Thread.getDefaultUncaughtExceptionHandler();
        if(!(currentHandler instanceof ErrbitExceptionHandler) && (environmentName.equals(ENVIRONMENT_PRODUCTION) || !notifyOnlyProduction)) {
            Thread.setDefaultUncaughtExceptionHandler(new ErrbitExceptionHandler(currentHandler));
        }

        // Load up current package name and version
        try {
            packageName = context.getPackageName();
            PackageInfo pi = context.getPackageManager().getPackageInfo(packageName, 0);
            if(pi.versionName != null) {
                versionName = pi.versionName;
            }
        } catch (Exception e) {}

        // Prepare the file storage location
        // TODO: Does this need to be done in a background thread?
        filePath = context.getFilesDir().getAbsolutePath() + UNSENT_EXCEPTION_PATH;
        File outFile = new File(filePath);
        outFile.mkdirs();
        diskStorageEnabled = outFile.exists();

        Log.d(LOG_TAG, "Registered and ready to handle exceptions.");

        // Flush any existing exception info
        new AsyncTask <Void, Void, Void>() {
            protected Void doInBackground(Void... voi) {
                flushExceptions();
                return null;
            }
        }.execute();
    }

    /**
     * Add a custom set of key/value data that will be sent as session data with each notification
     * @param extraData a Map of String -> String
     */
    public static void setExtraData(Map<String,String> extraData) {
        ErrbitNotifier.extraData = extraData;
    }

    // Fire an exception to airbrake manually
    public static void notify(final Throwable e) {
        notify(e, null);
    }

    public static void notify(final Throwable e, final Map<String,String> metaData) {
        if(e != null && diskStorageEnabled) {
            new AsyncTask <Void, Void, Void>() {
                 protected Void doInBackground(Void... voi) {
                     writeExceptionToDisk(e, metaData);
                     flushExceptions();
                     return null;
                 }
            }.execute();
        }
    }

    private static void writeExceptionToDisk(Throwable e, final Map<String,String> metaData) {
        try {
            // Set up the output stream
            int random = new Random().nextInt(99999);
            String filename = filePath + versionName + "-" + String.valueOf(random) + ".xml";
            BufferedWriter writer = new BufferedWriter(new FileWriter(filename));
            XmlSerializer s = Xml.newSerializer();
            s.setOutput(writer);

            // Start ridiculous xml building
            s.startDocument("UTF-8", true);

            // Top level tag
            s.startTag("", "notice");
            s.attribute("", "version", AIRBRAKE_API_VERSION);

            // Fill in the api key
            s.startTag("", "api-key");
            s.text(apiKey);
            s.endTag("", "api-key");

            // Fill in the notifier data
            s.startTag("", "notifier");
            s.startTag("", "name");
            s.text(NOTIFIER_NAME);
            s.endTag("", "name");
            s.startTag("", "version");
            s.text(NOTIFIER_VERSION);
            s.endTag("", "version");
            s.startTag("", "url");
            s.text(NOTIFIER_URL);
            s.endTag("", "url");
            s.endTag("", "notifier");

            // Fill in the error info
            s.startTag("", "error");
            s.startTag("", "class");
            s.text(e.getClass().getName());
            s.endTag("", "class");
            s.startTag("", "message");
            s.text("[" + versionName + "] " + e.toString());
            s.endTag("", "message");

            // Extract the stack traces
            s.startTag("", "backtrace");
            Throwable currentEx = e;
            while(currentEx != null) {
                // Catch some inner exceptions without discarding the entire report
                try {
                    StackTraceElement[] stackTrace = currentEx.getStackTrace();
                    for(StackTraceElement el : stackTrace) {
                        s.startTag("", "line");
                        try{
                          s.attribute("", "method", el.getClassName() + "." + el.getMethodName());
                          s.attribute("", "file", el.getFileName() == null ? "Unknown" : el.getFileName());
                          s.attribute("", "number", String.valueOf(el.getLineNumber()));
                        }catch(Throwable ex){
                            Log.v(LOG_TAG, "Exception caught:",ex);
                        }
                        s.endTag("", "line");
                    }

                    currentEx = currentEx.getCause();
                    if(currentEx != null) {
                        s.startTag("", "line");
                        try{
                          s.attribute("", "file", "### CAUSED BY ###: " + currentEx.toString());
                          s.attribute("", "number", "");
                        }catch(Throwable ex){
                          Log.v(LOG_TAG, "Exception caught:",ex);
                        }
                        s.endTag("", "line");
                    }
                } catch(Throwable innerException) {
                  Log.v(LOG_TAG, "Exception caught:",e);
                  break;
                }
            }
            s.endTag("", "backtrace");
            s.endTag("", "error");

            // Additional request info
            s.startTag("", "request");

            s.startTag("", "url");
            s.endTag("", "url");
            s.startTag("", "component");
            s.endTag("", "component");
            s.startTag("", "action");
            s.endTag("", "action");
            s.startTag("", "cgi-data");
            
            s.startTag("", "var");
            s.attribute("", "key", "Manufacturer");
            s.text(manufacturerDevice);
            s.endTag("", "var");
            
            s.startTag("", "var");
            s.attribute("", "key", "Device");
            s.text(phoneModel);
            s.endTag("", "var");
            
            s.startTag("", "var");
            s.attribute("", "key", "Brand");
            s.text(brandDevice);
            s.endTag("", "var");
            
            s.startTag("", "var");
            s.attribute("", "key", "Android Version");
            s.text(androidVersion);
            s.endTag("", "var");
            
            s.startTag("", "var");
            s.attribute("", "key", "App Version");
            s.text(versionName);
            s.endTag("", "var");

            // Extra info, if present
            if (extraData != null && !extraData.isEmpty()) {
                for (Map.Entry<String,String> extra : extraData.entrySet()) {
                    s.startTag("", "var");
                    s.attribute("", "key", extra.getKey());
                    s.text(extra.getValue());
                    s.endTag("", "var");
                }
            }

            // Metadata, if present
            if (metaData != null && !metaData.isEmpty()) {
                for (Map.Entry<String,String> extra : metaData.entrySet()) {
                    s.startTag("", "var");
                    s.attribute("", "key", extra.getKey());
                    s.text(extra.getValue());
                    s.endTag("", "var");
                }
            }

            s.endTag("", "cgi-data");
            s.endTag("", "request");

            // Production/development mode flag and app version
            s.startTag("", "server-environment");
            s.startTag("", "environment-name");
            s.text(environmentName);
            s.endTag("", "environment-name");
            s.startTag("", "app-version");
            s.text(versionName);
            s.endTag("", "app-version");
            s.endTag("", "server-environment");

            // Close document
            s.endTag("", "notice");
            s.endDocument();

            // Flush to disk
            writer.flush();
            writer.close();

            Log.d(LOG_TAG, "Writing new " + e.getClass().getName() + " exception to disk.");
        } catch (Exception ex) {
            Log.v(LOG_TAG, "Exception caught:",ex);
        }
    }

    private static void sendExceptionData(File file) {
        try {
            boolean sent = false;
            URL url = new URL(errbit_endpoint);
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            try {
                // Set up the connection
                conn.setDoOutput(true);
                conn.setRequestProperty("Content-Type", "text/xml; charset=utf-8");

                // Read from the file and send it
                FileInputStream is = new FileInputStream(file);
                OutputStream os = conn.getOutputStream();
                byte[] buffer = new byte[4096];
                int numRead;
                while((numRead = is.read(buffer)) >= 0) {
                  os.write(buffer, 0, numRead);
                }
                os.flush();
                os.close();
                is.close();

                // Flush the request through
                int response = conn.getResponseCode();
                Log.d(LOG_TAG, "Sent exception file " + file.getName() + " to Errbit. Got response code " + String.valueOf(response));

                sent = true;

            } catch(IOException e) {

                Log.v(LOG_TAG, "Exception caught:",e);

            } finally {

                // delete the file only if sending was successful 
                if ( sent ) {
                    file.delete();
                }

                conn.disconnect();
            }

        } catch(Exception e) {
          
          // unknown exception
          Log.v(LOG_TAG, "Exception caught:",e);

        }
    }

    private static synchronized void flushExceptions() {
        File exceptionDir = new File(filePath);
        if(exceptionDir.exists() && exceptionDir.isDirectory()) {
            File[] exceptions = exceptionDir.listFiles();
            for(File f : exceptions) {
                if(f.exists() && f.isFile()) {
                    sendExceptionData(f);
                }
            }
        }
    }
}