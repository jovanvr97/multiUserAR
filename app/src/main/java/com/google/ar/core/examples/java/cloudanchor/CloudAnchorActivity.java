/*
 * Copyright 2018 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.ar.core.examples.java.cloudanchor;

import android.graphics.Bitmap;
import android.hardware.Sensor;
import android.hardware.SensorEvent;
import android.hardware.SensorEventListener;
import android.hardware.SensorManager;
import android.media.Image;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.AsyncTask;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.StrictMode;
import android.os.SystemClock;
import android.support.annotation.GuardedBy;
import android.support.v7.app.AppCompatActivity;
import android.util.Base64;
import android.util.Log;
import android.view.GestureDetector;
import android.view.MotionEvent;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.ImageView;
import android.widget.Switch;
import android.widget.TextView;
import android.widget.Toast;
import com.google.ar.core.Anchor;
import com.google.ar.core.Anchor.CloudAnchorState;
import com.google.ar.core.ArCoreApk;
import com.google.ar.core.Camera;
import com.google.ar.core.Config;
import com.google.ar.core.Config.CloudAnchorMode;
import com.google.ar.core.Frame;
import com.google.ar.core.HitResult;
import com.google.ar.core.Plane;
import com.google.ar.core.Point;
import com.google.ar.core.Point.OrientationMode;
import com.google.ar.core.PointCloud;
import com.google.ar.core.Session;
import com.google.ar.core.Trackable;
import com.google.ar.core.TrackingState;
import com.google.ar.core.examples.java.common.helpers.CameraPermissionHelper;
import com.google.ar.core.examples.java.common.helpers.DisplayRotationHelper;
import com.google.ar.core.examples.java.common.helpers.FullScreenHelper;
import com.google.ar.core.examples.java.common.helpers.SnackbarHelper;
import com.google.ar.core.examples.java.common.rendering.BackgroundRenderer;
import com.google.ar.core.examples.java.common.rendering.ObjectRenderer;
import com.google.ar.core.examples.java.common.rendering.ObjectRenderer.BlendMode;
import com.google.ar.core.examples.java.common.rendering.PlaneRenderer;
import com.google.ar.core.examples.java.common.rendering.PointCloudRenderer;
import com.google.ar.core.exceptions.CameraNotAvailableException;
import com.google.ar.core.exceptions.UnavailableApkTooOldException;
import com.google.ar.core.exceptions.UnavailableArcoreNotInstalledException;
import com.google.ar.core.exceptions.UnavailableSdkTooOldException;
import com.google.common.base.Preconditions;
import com.google.firebase.database.DatabaseError;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UnsupportedEncodingException;
import java.net.HttpURLConnection;
import java.net.URL;
import java.net.URLEncoder;
import java.nio.IntBuffer;
import java.nio.charset.Charset;
import java.sql.Timestamp;
import java.text.SimpleDateFormat;
import java.util.HashMap;
import java.util.LinkedList;
import java.util.Map;

import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.opengles.GL10;

import static java.lang.Math.sqrt;

/**
 * Main Activity for the Cloud Anchor Example
 *
 * <p>This is a simple example that shows how to host and resolve anchors using ARCore Cloud Anchors
 * API calls. This app only has at most one anchor at a time, to focus more on the cloud aspect of
 * anchors.
 */
public class CloudAnchorActivity extends AppCompatActivity implements GLSurfaceView.Renderer, SensorEventListener {
  private static final String TAG = CloudAnchorActivity.class.getSimpleName();
  private static final float[] OBJECT_COLOR = new float[] {139.0f, 195.0f, 74.0f, 255.0f};


  private enum HostResolveMode {
    NONE,
    HOSTING,
    RESOLVING,
  }


  //WORKING WITH IMU SENSORS
  //WHEN VALUE OF THE SENSOR CHANGES IT WILL CALL METHOD onSensorChanged
  //WE REGISTERED ONLY TWO TYPES OF SENSORS - ACCELEROMETER and GYROSCOPE
  //timesW is list of timestamps when you capture gyro readings
  //timesAcc is list of timestamps when you capture acc readings
  private SensorManager sensorManager;
  private LinkedList<Long> timesW = new LinkedList<>();
  private LinkedList<Double> w = new LinkedList<>();
  private LinkedList<Float> accX = new LinkedList<>();
  private LinkedList<Float> accY = new LinkedList<>();
  private LinkedList<Float> accZ = new LinkedList<>();
  private LinkedList<Long> timesAcc = new LinkedList<>();
  @Override
  public void onSensorChanged(SensorEvent sensorEvent) {
    if(sensorEvent.sensor.getType() == Sensor.TYPE_GYROSCOPE) {
      timesW.add(SystemClock.elapsedRealtimeNanos());
      float x = sensorEvent.values[0];
      float y = sensorEvent.values[1];
      float z = sensorEvent.values[2];
      w.add(sqrt(x * x + y * y + z * z));
    }
    else{
      timesAcc.add(SystemClock.elapsedRealtimeNanos());
      accX.add(sensorEvent.values[0]);
      accY.add(sensorEvent.values[1]);
      accZ.add(sensorEvent.values[2]);
    }
  }

  @Override
  public void onAccuracyChanged(Sensor sensor, int accuracy) {

  }

  //YOU DO RENDEREING WITH GL SURFACE RENDERER

  // Rendering. The Renderers are created here, and initialized when the GL surface is created.
  private GLSurfaceView surfaceView;
  private final BackgroundRenderer backgroundRenderer = new BackgroundRenderer();

  //OBJECT RENDERER IS IMPORTANT FOR YOUR HOLOGRAMS, HOW 3D MODELS ARE RENDERED ON SCREEN
  private ObjectRenderer virtualObject = null;
  private final PlaneRenderer planeRenderer = new PlaneRenderer();
  private final PointCloudRenderer pointCloudRenderer = new PointCloudRenderer();

  private boolean installRequested;

//anchors is list of all anchors that user hosted or resolved
  private LinkedList<Anchor> anchors = new LinkedList<>();
  private boolean firstUsed = false;
  private long frameTaken;
  //visibleAnchors are only the anchors currently in the user's view
  private LinkedList<String> visibleAnchors = new LinkedList<>();
  //bitmaps is array of the last 100 frames, position is the postion of the tail of circular buffer bitmpas
  private Bitmap[] bitmaps = new Bitmap[100];
  private int position = 0;
  //dimensions of screen, widht and height
  private int mHeight;
  private int mWidth;
  //boolean that says if the user wants to take picture/ it becomes true when you press HOST button
  private boolean takePicture = false;
  //this is important to know what is our result, and which object should be rendered
  private FinalLabel finalLabel=new FinalLabel();
  //the names or the IDs of the anchors, it is synchronized with anchors list, means that anchorIDs[5] is the ID of the anchors[5]
  LinkedList<String> anchorIDs = new LinkedList<>();

  // Temporary matrices allocated here to reduce number of allocations for each frame.
  private final float[] anchorMatrix = new float[16];
  private final float[] viewMatrix = new float[16];
  private final float[] projectionMatrix = new float[16];

    private TapHelper tapHelper;

    // The asset ID to download and display.
    private static final String ASSET_ID = "6b7Ul6MeLrJ";

    // Scale factor to apply to asset when displaying.

    // Our background thread, which does all of the heavy lifting so we don't block the main thread.
    private HandlerThread mBackgroundThread;

    // Handler for the background thread, to which we post background thread tasks.
    private Handler mBackgroundThreadHandler;

    // The AsyncFileDownloader responsible for downloading a set of data files from Poly.
    private AsyncFileDownloader mFileDownloader;

    // When we're finished downloading the asset files, we flip this boolean to true to
    // indicate to the GL thread that it can import and load the model.
    private volatile boolean mReadyToImport;

    // Attributions text to display for the object (title and author).
    private String mAttributionText = "";

    // Have we already shown the attribution toast?
    private boolean mShowedAttributionToast;

  // Locks needed for synchronization
  private final Object singleTapLock = new Object();
  private final Object anchorLock = new Object();

  // Tap handling and UI.
  private GestureDetector gestureDetector;
  private final SnackbarHelper snackbarHelper = new SnackbarHelper();
  private DisplayRotationHelper displayRotationHelper;
  //you have two buttons, one for hosting (enable it only for administrator) and one for resolving for all the users
  private Button hostButton;
  private Button resolveButton;
  private TextView roomCodeText;
  //if the user wants multi or single user scenario
  private boolean multiUser = false;

  @GuardedBy("singleTapLock")
  private MotionEvent queuedSingleTap;

  private Session session;

  @GuardedBy("anchorLock")
  private Anchor anchor;

  // Cloud Anchor Components.
  private FirebaseManager firebaseManager;
  private final CloudAnchorManager cloudManager = new CloudAnchorManager();
  private HostResolveMode currentMode;
  private RoomCodeAndCloudAnchorIdListener hostListener;


  //THIS METHOD IS CALLED WHEN YOU OPEN THE APPLICATION/START
  //YOU NEED TO REGISTER LISTENERS FOR SENSORS, GESTURE DETECTORS AND TO ASSIGN ON CLICK CALLBACK ROUTINES FOR BUTTONS
  @Override
  protected void onCreate(Bundle savedInstanceState) {
    super.onCreate(savedInstanceState);
    //THIS ALLOWS COMMUNICATION WITH PLAINTEXT
    //YOU DON'T HAVE TO SEND CYPHER TEXT
    StrictMode.ThreadPolicy policy = new StrictMode.ThreadPolicy.Builder()
            .permitAll().build();
    StrictMode.setThreadPolicy(policy);
    sensorManager = (SensorManager)getSystemService(SENSOR_SERVICE);
    sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_GYROSCOPE), SensorManager.SENSOR_DELAY_FASTEST);
    sensorManager.registerListener(this, sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER), SensorManager.SENSOR_DELAY_FASTEST);
    setContentView(R.layout.activity_main);
    surfaceView = findViewById(R.id.surfaceview);
    displayRotationHelper = new DisplayRotationHelper(this);
    // Set up tap listener.
      tapHelper = new TapHelper(/*context=*/ this);
      surfaceView.setOnTouchListener(tapHelper);
      // Create a background thread, where we will do the heavy lifting.
      mBackgroundThread = new HandlerThread("Worker");
      mBackgroundThread.start();
      mBackgroundThreadHandler = new Handler(mBackgroundThread.getLooper());

      // Request the asset from the Poly API.
      Log.d(TAG, "Requesting asset "+ ASSET_ID);
      PolyApi.GetAsset(ASSET_ID, mBackgroundThreadHandler, new AsyncHttpRequest.CompletionListener() {
          @Override
          public void onHttpRequestSuccess(byte[] responseBody) {
              // Successfully fetched asset information. This does NOT include the model's geometry,
              // it's just the metadata. Let's parse it.
              parseAsset(responseBody);
          }
          @Override
          public void onHttpRequestFailure(int statusCode, String message, Exception exception) {
              // Something went wrong with the request.
              handleRequestFailure(statusCode, message, exception);
          }
      });
      //your swich one/multi user
    Switch onOffSwitch = (Switch)  findViewById(R.id.on_off_switch);
    onOffSwitch.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
      @Override
      public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
        Log.v("Switch State=", ""+isChecked);
        multiUser = isChecked;
      }
    });
    gestureDetector =
        new GestureDetector(
            this,
            new GestureDetector.SimpleOnGestureListener() {
              @Override
              public boolean onSingleTapUp(MotionEvent e) {
                synchronized (singleTapLock) {
                  if (currentMode == HostResolveMode.HOSTING) {
                    queuedSingleTap = e;
                  }
                }
                return true;
              }

              @Override
              public boolean onDown(MotionEvent e) {
                return true;
              }
            });
    surfaceView.setOnTouchListener((v, event) -> gestureDetector.onTouchEvent(event));

    // Set up renderer.
    surfaceView.setPreserveEGLContextOnPause(true);
    surfaceView.setEGLContextClientVersion(2);
    surfaceView.setEGLConfigChooser(8, 8, 8, 8, 16, 0); // Alpha used for plane blending.
    surfaceView.setRenderer(this);
    surfaceView.setRenderMode(GLSurfaceView.RENDERMODE_CONTINUOUSLY);
    surfaceView.setWillNotDraw(false);
    installRequested = false;

    // Initialize UI components.
    hostButton = findViewById(R.id.host_button);
    hostButton.setOnClickListener((view) -> onHostButtonPress());
    resolveButton = findViewById(R.id.resolve_button);
    resolveButton.setOnClickListener((view) -> onResolveButtonPress());
    roomCodeText = findViewById(R.id.room_code_text);

    // Initialize Cloud Anchor variables.
    firebaseManager = new FirebaseManager(this);
    currentMode = HostResolveMode.NONE;
  }

  @Override
  protected void onResume() {
    super.onResume();

    if (session == null) {
      Exception exception = null;
      int messageId = -1;
      try {
        switch (ArCoreApk.getInstance().requestInstall(this, !installRequested)) {
          case INSTALL_REQUESTED:
            installRequested = true;
            return;
          case INSTALLED:
            break;
        }

        // ARCore requires camera permissions to operate. If we did not yet obtain runtime
        // permission on Android M and above, now is a good time to ask the user for it.
        if (!CameraPermissionHelper.hasCameraPermission(this)) {
          CameraPermissionHelper.requestCameraPermission(this);
          return;
        }
        session = new Session(this);
      } catch (UnavailableArcoreNotInstalledException e) {
        messageId = R.string.snackbar_arcore_unavailable;
        exception = e;
      } catch (UnavailableApkTooOldException e) {
        messageId = R.string.snackbar_arcore_too_old;
        exception = e;
      } catch (UnavailableSdkTooOldException e) {
        messageId = R.string.snackbar_arcore_sdk_too_old;
        exception = e;
      } catch (Exception e) {
        messageId = R.string.snackbar_arcore_exception;
        exception = e;
      }

      if (exception != null) {
        snackbarHelper.showError(this, getString(messageId));
        Log.e(TAG, "Exception creating session", exception);
        return;
      }

      // Create default config and check if supported.
      Config config = new Config(session);
      config.setCloudAnchorMode(CloudAnchorMode.ENABLED);
      session.configure(config);

      // Setting the session in the HostManager.
      cloudManager.setSession(session);
      // Show the inital message only in the first resume.
      snackbarHelper.showMessage(this, getString(R.string.snackbar_initial_message));
    }

    // Note that order matters - see the note in onPause(), the reverse applies here.
    try {
      session.resume();
    } catch (CameraNotAvailableException e) {
      // In some cases (such as another camera app launching) the camera may be given to
      // a different app instead. Handle this properly by showing a message and recreate the
      // session at the next iteration.
      snackbarHelper.showError(this, getString(R.string.snackbar_camera_unavailable));
      session = null;
      return;
    }
    surfaceView.onResume();
    displayRotationHelper.onResume();
  }

  @Override
  public void onPause() {
    super.onPause();
    if (session != null) {
      // Note that the order matters - GLSurfaceView is paused first so that it does not try
      // to query the session. If Session is paused before GLSurfaceView, GLSurfaceView may
      // still call session.update() and get a SessionPausedException.
      displayRotationHelper.onPause();
      surfaceView.onPause();
      session.pause();
    }
  }

  @Override
  public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
    if (!CameraPermissionHelper.hasCameraPermission(this)) {
      Toast.makeText(this, "Camera permission is needed to run this application", Toast.LENGTH_LONG)
          .show();
      if (!CameraPermissionHelper.shouldShowRequestPermissionRationale(this)) {
        // Permission denied with checking "Do not ask again".
        CameraPermissionHelper.launchPermissionSettings(this);
      }
      finish();
    }
  }

  @Override
  public void onWindowFocusChanged(boolean hasFocus) {
    super.onWindowFocusChanged(hasFocus);
    FullScreenHelper.setFullScreenOnWindowFocusChanged(this, hasFocus);
  }

  /**
   * Handles the most recent user tap.
   *
   * <p>We only ever handle one tap at a time, since this app only allows for a single anchor.
   *
   * @param frame the current AR frame
   * @param cameraTrackingState the current camera tracking state
   */
  private void handleTap(Frame frame, TrackingState cameraTrackingState) {
    // Handle taps. Handling only one tap per frame, as taps are usually low frequency
    // compared to frame rate.
    synchronized (singleTapLock) {
      synchronized (anchorLock) {
        // Only handle a tap if the anchor is currently null, the queued tap is non-null and the
        // camera is currently tracking.
        if (
            queuedSingleTap != null
            && cameraTrackingState == TrackingState.TRACKING) {
          Preconditions.checkState(
              currentMode == HostResolveMode.HOSTING,
              "We should only be creating an anchor in hosting mode.");
          for (HitResult hit : frame.hitTest(queuedSingleTap)) {
            if (shouldCreateAnchorWithHit(hit)) {
              Anchor newAnchor = hit.createAnchor();
              Preconditions.checkNotNull(hostListener, "The host listener cannot be null.");
              cloudManager.hostCloudAnchor(newAnchor, hostListener);
              if(newAnchor!=null && newAnchor.getCloudAnchorId()!=null && newAnchor.getCloudAnchorId().length()>0)
                setNewAnchor(newAnchor,newAnchor.getCloudAnchorId());
              snackbarHelper.showMessage(this, getString(R.string.snackbar_anchor_placed));
              break; // Only handle the first valid hit.
            }
          }
        }
      }
      queuedSingleTap = null;
    }
  }

  /** Returns {@code true} if and only if the hit can be used to create an Anchor reliably. */
  private static boolean shouldCreateAnchorWithHit(HitResult hit) {
    Trackable trackable = hit.getTrackable();
    if (trackable instanceof Plane) {
      // Check if the hit was within the plane's polygon.
      return ((Plane) trackable).isPoseInPolygon(hit.getHitPose());
    } else if (trackable instanceof Point) {
      // Check if the hit was against an oriented point.
      return ((Point) trackable).getOrientationMode() == OrientationMode.ESTIMATED_SURFACE_NORMAL;
    }
    return false;
  }

  @Override
  public void onSurfaceCreated(GL10 gl, EGLConfig config) {
    GLES20.glClearColor(0.1f, 0.1f, 0.1f, 1.0f);

    // Prepare the rendering objects. This involves reading shaders, so may throw an IOException.
    try {
      // Create the texture and pass it to ARCore session to be filled during update().
      backgroundRenderer.createOnGlThread(this);
      planeRenderer.createOnGlThread(this, "models/trigrid.png");
      pointCloudRenderer.createOnGlThread(this);

      //virtualObject.createOnGlThread(this, "models/andy.obj", "models/andy.png");
      //virtualObject.setMaterialProperties(0.0f, 2.0f, 0.5f, 6.0f);

      /*virtualObjectShadow.createOnGlThread(
          this, "models/andy_shadow.obj", "models/andy_shadow.png");
      virtualObjectShadow.setBlendMode(BlendMode.Shadow);
      virtualObjectShadow.setMaterialProperties(1.0f, 0.0f, 0.0f, 1.0f);*/
    } catch (IOException ex) {
      Log.e(TAG, "Failed to read an asset file", ex);
    }
  }

  @Override
  public void onSurfaceChanged(GL10 gl, int width, int height) {
    mWidth = width;
    mHeight = height;
    displayRotationHelper.onSurfaceChanged(width, height);
    GLES20.glViewport(0, 0, width, height);
  }
  private ObjectType objType = new ObjectType();
  private String previous = "";
  private static float ASSET_SCALE = 1f;
  @Override
  public void onDrawFrame(GL10 gl) {
    // Clear screen to notify driver it should not load any pixels from previous frame.
    GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT | GLES20.GL_DEPTH_BUFFER_BIT);
     /* if (mReadyToImport && virtualObject == null) {
          importDownloadedObject();
      }*/
    if(virtualObject==null){
      try{
        virtualObject = new ObjectRenderer();
        virtualObject.createOnGlThread(this, "models/andy.obj", "models/Andy_Diffuse.png");
        virtualObject.setMaterialProperties(0.0f, 3.5f, 1.0f, 6.0f);
      }catch(Exception e){}
    }

    if(!objType.type.equals(previous)) {
      try {
        virtualObject = new ObjectRenderer();
        /*
        virtualObject.createOnGlThread(this, "models/CactusWren.obj", "models/CactusWren_BaseColor.png");
        virtualObject.setMaterialProperties(0.0f, 3.5f, 1.0f, 6.0f);
      }catch(Exception e){e.printStackTrace();}*/
        System.out.println(objType.type + " type");
        switch (objType.type) {
          case "tv": {
            virtualObject.createOnGlThread(this, "models/1337 iMac.obj", "models/1337 iMac.png");
            virtualObject.setMaterialProperties(0.0f, 3.5f, 1.0f, 6.0f);
            ASSET_SCALE = 0.007f;
            previous = "tv";
            break;
          }
          case "keyboard": {
            virtualObject.createOnGlThread(this, "models/Keyboard.obj", "models/Computer Texture.png");
            virtualObject.setMaterialProperties(0.0f, 3.5f, 1.0f, 6.0f);
            ASSET_SCALE = 0.003f;
            previous ="keyboard";
            break;
          }
          case "cell phone": {
            virtualObject.createOnGlThread(this, "models/mobile-phone.obj", "models/phone.png");
            virtualObject.setMaterialProperties(0.0f, 3.5f, 1.0f, 6.0f);
            ASSET_SCALE = 0.007f;
            previous ="cell phone";
            break;
          }
          case "cup":{
            virtualObject.createOnGlThread(this, "models/CHAHIN_COFFEE_CUP.obj", "models/CHAHIN_COFFEE_CUP_TEXTURE.jpg");
            virtualObject.setMaterialProperties(0.0f, 3.5f, 1.0f, 6.0f);
            ASSET_SCALE = 0.1f;
            previous ="cup";
            break;
          }
          case "person":{
            virtualObject.createOnGlThread(this, "models/Alien.obj", "models/Alien_BaseColor.png");
            virtualObject.setMaterialProperties(0.0f, 3.5f, 1.0f, 6.0f);
            ASSET_SCALE = 0.02f;
            previous ="person";
            break;
          }
          case "pens":{
            virtualObject.createOnGlThread(this, "models/Pencil_01.obj", "models/phone.png");
            virtualObject.setMaterialProperties(0.0f, 3.5f, 1.0f, 6.0f);
            ASSET_SCALE = 0.03f;
            previous ="pens";
            break;
            }
          case "shoes":{
            virtualObject.createOnGlThread(this, "models/zapatillas.obj", "models/zapatillas_uv.jpg");
            virtualObject.setMaterialProperties(0.0f, 3.5f, 1.0f, 6.0f);
            ASSET_SCALE = 0.1f;
            previous ="shoes";
            break;
          }
          default: {
            virtualObject.createOnGlThread(this, "models/andy.obj", "models/Andy_Diffuse.png");
            virtualObject.setMaterialProperties(0.0f, 3.5f, 1.0f, 6.0f);
            ASSET_SCALE = 1.0f;
            previous = "default";
          }
        }
      } catch (Exception e) {
        e.printStackTrace();
      }
      // }
    }
    if (session == null) {
      return;
    }
    // Notify ARCore session that the view size changed so that the perspective matrix and
    // the video background can be properly adjusted.
    displayRotationHelper.updateSessionIfNeeded(session);

    try {
      session.setCameraTextureName(backgroundRenderer.getTextureId());

      // Obtain the current frame from ARSession. When the configuration is set to
      // UpdateMode.BLOCKING (it is by default), this will throttle the rendering to the
      // camera framerate.
      Frame frame = session.update();
      frameTaken = frame.getTimestamp();
      Image img = frame.acquireCameraImage();
      SaveFrame sf = new SaveFrame();
      sf.image = img;
      sf.activity = this;
      sf.position = position;
      sf.bitmaps = bitmaps;
      sf.doInBackground("");
      position++;
      visibleAnchors.clear();
      if(position==100)
        position = 0;
      Camera camera = frame.getCamera();
      TrackingState cameraTrackingState = camera.getTrackingState();

      // Notify the cloudManager of all the updates.
      cloudManager.onUpdate();

      // Handle user input.
      handleTap(frame, cameraTrackingState);
     //   handleTapSingle(frame, camera);
      // If frame is ready, render camera preview image to the GL surface.
      backgroundRenderer.draw(frame);

      // If not tracking, don't draw 3d objects.
      if (cameraTrackingState == TrackingState.PAUSED) {
        return;
      }

      // Get camera and projection matrices.
      camera.getViewMatrix(viewMatrix, 0);
      camera.getProjectionMatrix(projectionMatrix, 0, 0.1f, 100.0f);

      // Visualize tracked points.
      // Use try-with-resources to automatically release the point cloud.
      try (PointCloud pointCloud = frame.acquirePointCloud()) {
        pointCloudRenderer.update(pointCloud);
        pointCloudRenderer.draw(viewMatrix, projectionMatrix);
      }

      // Visualize planes.
      planeRenderer.drawPlanes(
          session.getAllTrackables(Plane.class), camera.getDisplayOrientedPose(), projectionMatrix);

      // Check if the anchor can be visualized or not, and get its pose if it can be.
      boolean shouldDrawAnchor = false;
      synchronized (anchorLock) {
        if (anchor != null && anchor.getTrackingState() == TrackingState.TRACKING) {
          // Get the current pose of an Anchor in world space. The Anchor pose is updated
          // during calls to session.update() as ARCore refines its estimate of the world.
          anchor.getPose().toMatrix(anchorMatrix, 0);
          shouldDrawAnchor = true;
        }
      }

      // Visualize anchor.
      if (shouldDrawAnchor) {
        float[] colorCorrectionRgba = new float[4];
        frame.getLightEstimate().getColorCorrection(colorCorrectionRgba, 0);
        LinkedList<String> drawnAnchors = new LinkedList<>();
        // Update and draw the model and its shadow.
        for(int i=anchors.size()-1;i>=0;i--) {
          anchors.get(i).getPose().toMatrix(anchorMatrix, 0);
          float scaleFactor = 1.0f;
          float[] projmtx = new float[16];
          camera.getProjectionMatrix(projmtx, 0, 0.1f, 100.0f);

          // Get camera matrix and draw.
          float[] viewmtx = new float[16];
          camera.getViewMatrix(viewmtx, 0);

          float[] anchorMatrix = new float[16];
          anchors.get(i).getPose().toMatrix(anchorMatrix, 0);
          float[] world2screenMatrix =
                  calculateWorld2CameraMatrix(anchorMatrix, viewmtx, projmtx);
          double[] anchor_2d =  world2Screen(mWidth, mHeight, world2screenMatrix);
          float[] scaleMatrix = new float[16];
          float[] modelXscale = new float[16];
          float[] viewXmodelXscale = new float[16];

          Matrix.setIdentityM(scaleMatrix, 0);
          scaleMatrix[0] = scaleFactor;
          scaleMatrix[5] = scaleFactor;
          scaleMatrix[10] = scaleFactor;

          Matrix.multiplyMM(modelXscale, 0, anchorMatrix, 0, scaleMatrix, 0);
          Matrix.multiplyMM(viewXmodelXscale, 0, viewmtx, 0, modelXscale, 0);

          float zPos = viewXmodelXscale[14];
          if (anchor_2d[0] > 0 && anchor_2d[0] < mWidth && anchor_2d[1] > 0 && anchor_2d[1] < mHeight && zPos<0 ) {
            visibleAnchors.add(anchorIDs.get(i));
          }

          if(!drawnAnchors.contains(anchorIDs.get(i))) {
            drawnAnchors.add(anchorIDs.get(i));
            virtualObject.updateModelMatrix(anchorMatrix, ASSET_SCALE);
            //virtualObjectShadow.updateModelMatrix(anchorMatrix, scaleFactor);
            virtualObject.draw(viewMatrix, projectionMatrix, colorCorrectionRgba);
            //virtualObjectShadow.draw(viewMatrix, projectionMatrix, colorCorrectionRgba, OBJECT_COLOR);
          }
        }
      }
      if(takePicture)
      {
        //snackbarHelper.showMessageWithDismiss(this, "REQUEST SEEN");
        takePicture = false;
        SavePicture();
      }
      else {
        if (!firstUsed) {
          firstUsed = true;
        }
      }
    } catch (Throwable t) {
      // Avoid crashing the application due to unhandled exceptions.
      Log.e(TAG, "Exception on the OpenGL thread", t);
    }
  }
  double[] world2Screen(int screenWidth, int screenHeight, float[] world2cameraMatrix)
  {
    float[] origin = {0f, 0f, 0f, 1f};
    float[] ndcCoord = new float[4];
    Matrix.multiplyMV(ndcCoord, 0,  world2cameraMatrix, 0,  origin, 0);

    ndcCoord[0] = ndcCoord[0]/ndcCoord[3];
    ndcCoord[1] = ndcCoord[1]/ndcCoord[3];

    double[] pos_2d = new double[]{0,0};
    pos_2d[0] = screenWidth  * ((ndcCoord[0] + 1.0)/2.0);
    pos_2d[1] = screenHeight * (( 1.0 - ndcCoord[1])/2.0);

    return pos_2d;
  }
  public float[] calculateWorld2CameraMatrix(float[] modelmtx, float[] viewmtx, float[] prjmtx) {

    float scaleFactor = 1.0f;
    float[] scaleMatrix = new float[16];
    float[] modelXscale = new float[16];
    float[] viewXmodelXscale = new float[16];
    float[] world2screenMatrix = new float[16];

    Matrix.setIdentityM(scaleMatrix, 0);
    scaleMatrix[0] = scaleFactor;
    scaleMatrix[5] = scaleFactor;
    scaleMatrix[10] = scaleFactor;

    Matrix.multiplyMM(modelXscale, 0, modelmtx, 0, scaleMatrix, 0);
    Matrix.multiplyMM(viewXmodelXscale, 0, viewmtx, 0, modelXscale, 0);
    Matrix.multiplyMM(world2screenMatrix, 0, prjmtx, 0, viewXmodelXscale, 0);

    return world2screenMatrix;
  }

  private static final SimpleDateFormat sdf = new SimpleDateFormat("yyyy.MM.dd.HH.mm.ss");
  public void SavePicture() throws IOException {
    Helper helper = new Helper();
    String gyroscope = helper.getConfigValue(this,"gyroscope");
    String accelerometer = helper.getConfigValue(this,"accelerometer");
    String anchors = helper.getConfigValue(this,"anchors");
    String previousFrames = helper.getConfigValue(this,"previousFrames");
    String url = helper.getConfigValue(this,"url");
      int positionTake = position - 1;
      if(positionTake<0)
          positionTake = 99;
   Bitmap bmp = bitmaps[positionTake];
    bmp = Bitmap.createScaledBitmap(
            bmp, mWidth, mHeight, false);
    LinkedList<Double> rightAngularVelocities = new LinkedList<>();
    LinkedList<Float> rightAccX = new LinkedList<>();
    LinkedList<Float> rightAccY = new LinkedList<>();
    LinkedList<Float> rightAccZ = new LinkedList<>();
    boolean moveOnGyro = true;
    int indexGyro = w.size() -1;
    while(indexGyro>=0 && moveOnGyro){
      if(timesW.get(indexGyro)<=frameTaken + 300000000  && timesW.get(indexGyro)>=frameTaken-300000000)
        rightAngularVelocities.add(w.get(indexGyro));
      else
        moveOnGyro = false;
      indexGyro--;
    }
    boolean moveOnAcc = true;
    int indexAcc = accX.size() - 1;
    while(moveOnAcc && indexAcc>=0){
      if(timesAcc.get(indexAcc)<=frameTaken+300000000 && timesAcc.get(indexAcc)>=frameTaken-300000000) {
        rightAccX.add(accX.get(indexAcc));
        rightAccY.add(accX.get(indexAcc));
        rightAccZ.add(accX.get(indexAcc));
      }
      else{
        moveOnAcc = false;
      }
      indexAcc--;
    }
    HashMap<String, String> hm = new HashMap<>();
    hm.put("image_bytes",BitMapToString(bmp));
    if(anchors.equals("true"))
      hm.put("anchor_IDs",visibleAnchors.toString());
    double sumW = 0;
    double sumAccX = 0;
    double sumAccY = 0;
    double sumAccZ = 0;
    for(int i=0;i<rightAngularVelocities.size();i++)
      sumW+=rightAngularVelocities.get(i);
    for(int i=0;i<rightAccX.size();i++) {
      sumAccX += rightAccX.get(i);
      sumAccY += rightAccY.get(i);
      sumAccZ += rightAccZ.get(i);
    }
    double meanW = sumW/rightAngularVelocities.size();
    if(gyroscope.equals("true"))
      hm.put("gyro_readings",meanW+"");
    double meanAccX = sumAccX/rightAccX.size();
    double meanAccY = sumAccY/rightAccY.size();
    double meanAccZ = sumAccZ/rightAccZ.size();
    if(accelerometer.equals("true")) {
      hm.put("accX_readings", meanAccX + "");
      hm.put("accY_readings", meanAccY + "");
      hm.put("accZ_readings", meanAccZ + "");
    }
    if(previousFrames.equals("true")) {
      int cntValid = 0;
      for (int i = 0; i < 100; i++) {
        if (bitmaps[i] != null) {
          cntValid++;
          hm.put("frame" + i, BitMapToString(bitmaps[i]));
        }
      }
      hm.put("valid_frames",""+cntValid);
    }
    if(multiUser)
      hm.put("multiUser","multi");
    else
      hm.put("multiUser","single");
    //snackbarHelper.showMessageWithDismiss(this, "SENDING MESSAGE TO SERVER");
    CallAPI upload = new CallAPI();
    upload.activity = this;
    upload.snack = snackbarHelper;
    upload.finalLabel = finalLabel;
    upload.objType = objType;
    upload.doInBackground(url,getPostDataString(hm));
    timesW.clear();
    w.clear();
    accX.clear();
    accY.clear();
    accZ.clear();
  }
  public String BitMapToString(Bitmap bitmap){
    ByteArrayOutputStream baos=new  ByteArrayOutputStream();
    bitmap.compress(Bitmap.CompressFormat.PNG,100, baos);
    byte [] b=baos.toByteArray();
    String temp= Base64.encodeToString(b, Base64.DEFAULT);
    return temp;
  }
  private String getPostDataString(HashMap<String, String> params) throws UnsupportedEncodingException {
    StringBuilder result = new StringBuilder();
    boolean first = true;
    for(Map.Entry<String, String> entry : params.entrySet()){
      if (first)
        first = false;
      else
        result.append("&");

      result.append(URLEncoder.encode(entry.getKey(), "UTF-8"));
      result.append("=");
      result.append(URLEncoder.encode(entry.getValue(), "UTF-8"));
    }

    return result.toString();
  }

  /** Sets the new value of the current anchor. Detaches the old anchor, if it was non-null. */
  private void setNewAnchor(Anchor newAnchor, String anchorID) {
    synchronized (anchorLock) {
      /*if (anchor != null) {
        anchor.detach();
      }*/
      anchor = newAnchor;
      anchors.add(anchor);
      anchorIDs.add(anchorID);
    }
  }

  /** Callback function invoked when the Host Button is pressed. */
  private void onHostButtonPress() {
     // snackbarHelper.showMessageWithDismiss(this, "HOST BUTTON PRESSED");
    takePicture = true;
    //IF YOU WANT TO HOST JUST REMOVE COMMENTS
   /* if (currentMode == HostResolveMode.HOSTING) {
      resetMode();
      return;
    }
    if (hostListener != null) {
      return;
    }

    resolveButton.setEnabled(false);
    hostButton.setText(R.string.cancel);
    snackbarHelper.showMessageWithDismiss(this, getString(R.string.snackbar_on_host));

    hostListener = new RoomCodeAndCloudAnchorIdListener();
    firebaseManager.getNewRoomCode(hostListener);*/
  }

  /** Callback function invoked when the Resolve Button is pressed. */
  private void onResolveButtonPress() {
    if (currentMode == HostResolveMode.RESOLVING) {
      resetMode();
      return;
    }
    new GetUrlContentTask(this).execute("http://10.197.53.148:5000/api/test2");
  }

  private class GetUrlContentTask extends AsyncTask<String, Integer, String> {
    public CloudAnchorActivity cl;
    public GetUrlContentTask(CloudAnchorActivity c){
      cl=c;
    }
    protected String doInBackground(String... urls) {
      String content= "";
      try {
        URL url = new URL(urls[0]);
        HttpURLConnection connection = (HttpURLConnection) url.openConnection();
        connection.setRequestMethod("GET");
        connection.setDoOutput(true);
        connection.setConnectTimeout(5000);
        connection.setReadTimeout(5000);
        connection.connect();
        BufferedReader rd = new BufferedReader(new InputStreamReader(connection.getInputStream()));
        content = "";
        String line;
        while ((line = rd.readLine()) != null) {
          content += line + "\n";
        }
      }catch(Exception e){e.printStackTrace();}
      return content;
    }

    protected void onProgressUpdate(Integer... progress) {
    }

    protected void onPostExecute(String result) {
      // this is executed on the main thread after the process is over
      // update your UI here\
      try {
        JSONObject job = new JSONObject(result);
        int num = job.getInt("nAnchorsHigh");
        ResolveDialogFragment dialogFragment = new ResolveDialogFragment(num);
        dialogFragment.num = num;
        dialogFragment.setOkListener(cl::onRoomCodeEntered);
        dialogFragment.show(getSupportFragmentManager(), "ResolveDialog");
      }catch(Exception e){e.printStackTrace();}
    }
  }

  /** Resets the mode of the app to its initial state and removes the anchors. */
  private void resetMode() {
    hostButton.setText(R.string.host_button_text);
    hostButton.setEnabled(true);
    resolveButton.setText(R.string.resolve_button_text);
    resolveButton.setEnabled(true);
    roomCodeText.setText(R.string.initial_room_code);
    currentMode = HostResolveMode.NONE;
    firebaseManager.clearRoomListener();
    hostListener = null;
    //setNewAnchor(null);
    snackbarHelper.hide(this);
    cloudManager.clearListeners();
  }

  /** Callback function invoked when the user presses the OK button in the Resolve Dialog. */
  private void onRoomCodeEntered(Long roomCode) {
    currentMode = HostResolveMode.RESOLVING;
    hostButton.setEnabled(false);
    resolveButton.setText(R.string.cancel);
    roomCodeText.setText(String.valueOf(roomCode));
    snackbarHelper.showMessageWithDismiss(this, getString(R.string.snackbar_on_resolve));

    // Register a new listener for the given room.
    long room = roomCode;
    firebaseManager.registerNewListenerForRoom(
            room,
            (cloudAnchorId) -> {
              findAnchor(room, cloudAnchorId);
            });
  }
  private void findAnchor(long room,String cloudAnchorId){
    // When the cloud anchor ID is available from Firebase.
    cloudManager.resolveCloudAnchor(
            cloudAnchorId,
            (anchor) -> {
              // When the anchor has been resolved, or had a final error state.
              CloudAnchorState cloudState = anchor.getCloudAnchorState();
              if (cloudState.isError()) {
                Log.w(
                        TAG,
                        "The anchor in room "
                                + room
                                + " could not be resolved. The error state was "
                                + cloudState);
                // snackbarHelper.showMessageWithDismiss(
                //         CloudAnchorActivity.this,
                //        getString(R.string.snackbar_resolve_error, cloudState));
                return;
              }
              snackbarHelper.showMessageWithDismiss(
                      CloudAnchorActivity.this, getString(R.string.snackbar_resolve_success));
              if(anchor!=null && cloudAnchorId!=null && cloudAnchorId.length()>0)
                setNewAnchor(anchor, cloudAnchorId);
            });
    if(room>=320) {
      firebaseManager.registerNewListenerForRoom(
              room-1,
              (cloudAnchorId1) -> {
                findAnchor(room-1, cloudAnchorId1);
              });
    }
  }

    // NOTE: this runs on the background thread.
    private void parseAsset(byte[] assetData) {
        Log.d(TAG, "Got asset response (" + assetData.length + " bytes). Parsing.");
        String assetBody = new String(assetData, Charset.forName("UTF-8"));
        Log.d(TAG, assetBody);
        try {
            JSONObject response = new JSONObject(assetBody);
            String displayName = response.getString("displayName");
            String authorName = response.getString("authorName");
            Log.d(TAG, "Display name: " + displayName);
            Log.d(TAG, "Author name: " + authorName);
            mAttributionText = displayName + " by " + authorName;

            // The asset may have several formats (OBJ, GLTF, FBX, etc). We will look for the OBJ format.
            JSONArray formats = response.getJSONArray("formats");
            boolean foundObjFormat = false;
            for (int i = 0; i < formats.length(); i++) {
                JSONObject format = formats.getJSONObject(i);
                if (format.getString("formatType").equals("OBJ")) {
                    // Found the OBJ format. The format gives us the URL of the data files that we should
                    // download (which include the OBJ file, the MTL file and the textures). We will now
                    // request those files.
                    requestDataFiles(format);
                    foundObjFormat = true;
                    break;
                }
            }
            if (!foundObjFormat) {
                // If this happens, it's because the asset doesn't have a representation in the OBJ
                // format. Since this simple sample code can only parse OBJ, we can't proceed.
                // But other formats might be available, so if your client supports multiple formats,
                // you could still try a different format instead.
                Log.e(TAG, "Could not find OBJ format in asset.");
                return;
            }
        } catch (JSONException jsonException) {
            Log.e(TAG, "JSON parsing error while processing response: " + jsonException);
            jsonException.printStackTrace();
        }
    }

    // Requests the data files for the OBJ format.
    // NOTE: this runs on the background thread.
    private void requestDataFiles(JSONObject objFormat) throws JSONException {
        // objFormat has the list of data files for the OBJ format (OBJ file, MTL file, textures).
        // We will use a AsyncFileDownloader to download all those files.
        mFileDownloader = new AsyncFileDownloader();

        // The "root file" is the OBJ.
        JSONObject rootFile = objFormat.getJSONObject("root");
        mFileDownloader.add(rootFile.getString("relativePath"), rootFile.getString("url"));

        // The "resource files" are the MTL file and textures.
        JSONArray resources = objFormat.getJSONArray("resources");
        for (int i = 0; i < resources.length(); i++) {
            JSONObject resourceFile = resources.getJSONObject(i);
            String path = resourceFile.getString("relativePath");
            String url = resourceFile.getString("url");
            // For this example, we only care about OBJ and PNG files.
            if (path.toLowerCase().endsWith(".obj") || path.toLowerCase().endsWith(".png")) {
                mFileDownloader.add(path, url);
            }
        }

        // Now start downloading the data files. When this is done, the callback will call
        // processDataFiles().
        Log.d(TAG, "Starting to download data files, # files: " + mFileDownloader.getEntryCount());
        mFileDownloader.start(mBackgroundThreadHandler, new AsyncFileDownloader.CompletionListener() {
            @Override
            public void onPolyDownloadFinished(AsyncFileDownloader downloader) {
                if (downloader.isError()) {
                    Log.e(TAG, "Failed to download data files for asset.");
                    return;
                }
                // Signal to the GL thread that download is complete, so it can go ahead and
                // import the model.
                Log.d(TAG, "Download complete, ready to import model.");
                mReadyToImport = true;
            }
        });
    }

    // NOTE: this runs on the background thread.
    private void handleRequestFailure(int statusCode, String message, Exception exception) {
        // NOTE: because this is a simple sample, we don't have any real error handling logic
        // other than just printing the error. In an actual app, this is where you would take
        // appropriate action according to your app's use case. You could, for example, surface
        // the error to the user or retry the request later.
        Log.e(TAG, "Request failed. Status code " + statusCode + ", message: " + message +
                ((exception != null) ? ", exception: " + exception : ""));
        if (exception != null) exception.printStackTrace();
    }

    private void showAttributionToast() {
        mShowedAttributionToast = true;
        runOnUiThread(new Runnable() {
            @Override
            public void run() {
                // NOTE: we use a toast for showing attribution in this sample because it's the
                // simplest way to accomplish this. In your app, you are not required to use
                // a toast. You can display this attribution information in the most appropriate
                // way for your application.
                Toast.makeText(CloudAnchorActivity.this, mAttributionText, Toast.LENGTH_LONG).show();
            }
        });
    }

  /**
   * Listens for both a new room code and an anchor ID, and shares the anchor ID in Firebase with
   * the room code when both are available.
   */
  private final class RoomCodeAndCloudAnchorIdListener
      implements CloudAnchorManager.CloudAnchorListener, FirebaseManager.RoomCodeListener {

    private Long roomCode;
    private String cloudAnchorId;

    @Override
    public void onNewRoomCode(Long newRoomCode) {
      Preconditions.checkState(roomCode == null, "The room code cannot have been set before.");
      roomCode = newRoomCode;
      roomCodeText.setText(String.valueOf(roomCode));
      snackbarHelper.showMessageWithDismiss(
          CloudAnchorActivity.this, getString(R.string.snackbar_room_code_available));
      checkAndMaybeShare();
      synchronized (singleTapLock) {
        // Change currentMode to HOSTING after receiving the room code (not when the 'Host' button
        // is tapped), to prevent an anchor being placed before we know the room code and able to
        // share the anchor ID.
        currentMode = HostResolveMode.HOSTING;
      }
    }

    @Override
    public void onError(DatabaseError error) {
      Log.w(TAG, "A Firebase database error happened.", error.toException());
      snackbarHelper.showError(
          CloudAnchorActivity.this, getString(R.string.snackbar_firebase_error));
    }

    @Override
    public void onCloudTaskComplete(Anchor anchor) {
      CloudAnchorState cloudState = anchor.getCloudAnchorState();
      if (cloudState.isError()) {
        Log.e(TAG, "Error hosting a cloud anchor, state " + cloudState);
        snackbarHelper.showMessageWithDismiss(
            CloudAnchorActivity.this, getString(R.string.snackbar_host_error, cloudState));
        return;
      }
      Preconditions.checkState(
          cloudAnchorId == null, "The cloud anchor ID cannot have been set before.");
      cloudAnchorId = anchor.getCloudAnchorId();
      if(anchor!=null && cloudAnchorId!=null && cloudAnchorId.length()>0) {
        setNewAnchor(anchor, cloudAnchorId);

        takePicture = true;
      }
      checkAndMaybeShare();
    }
    private int cnt = 0;
    private void checkAndMaybeShare() {
      if (roomCode == null || cloudAnchorId == null) {
          snackbarHelper.showMessageWithDismiss(
                  CloudAnchorActivity.this, "NULL "+cnt);
          cnt++;
        return;
      }
      firebaseManager.storeAnchorIdInRoom(roomCode, cloudAnchorId);
      snackbarHelper.showMessageWithDismiss(
          CloudAnchorActivity.this, getString(R.string.snackbar_cloud_id_shared));
    }
  }
}
