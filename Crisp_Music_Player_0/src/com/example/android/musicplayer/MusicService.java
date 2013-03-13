/*   
 * Copyright (C) 2011 The Android Open Source Project
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

package com.example.android.musicplayer;

import java.io.IOException;

import android.app.Notification;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.app.Service;
import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.media.AudioManager;
import android.media.MediaMetadataRetriever;
import android.media.MediaPlayer;
import android.media.MediaPlayer.OnCompletionListener;
import android.media.MediaPlayer.OnErrorListener;
import android.media.MediaPlayer.OnPreparedListener;
import android.media.RemoteControlClient;
import android.net.Uri;
import android.net.wifi.WifiManager;
import android.net.wifi.WifiManager.WifiLock;
import android.os.IBinder;
import android.os.PowerManager;
import android.os.Bundle;
import android.util.Log;
import android.widget.Toast;

import java.io.FileNotFoundException;
import java.io.IOException;
import com.example.android.musicplayer.R;
import com.example.android.musicplayer.MusicRetriever.Item;


/**
 * Service that handles media playback. This is the Service through which we perform all the media
 * handling in our application. Upon initialization, it starts a {@link MusicRetriever} to scan
 * the user's media. Then, it waits for Intents (which come from our main activity,
 * {@link MainActivity}, which signal the service to perform specific operations: Play, Pause,
 * Rewind, Skip, etc.
 */
public class MusicService extends Service implements OnCompletionListener, OnPreparedListener,
                OnErrorListener, MusicFocusable,
                PrepareMusicRetrieverTask.MusicRetrieverPreparedListener {

    // The tag we put on debug messages
    final static String TAG = "RandomMusicPlayer";

    // These are the Intent actions that we are prepared to handle. Notice that the fact these
    // constants exist in our class is a mere convenience: what really defines the actions our
    // service can handle are the <action> tags in the <intent-filters> tag for our service in
    // AndroidManifest.xml.
    public static final String ACTION_TOGGLE_PLAYBACK =
            "com.example.android.musicplayer.action.TOGGLE_PLAYBACK";
    public static final String ACTION_PLAY = "com.example.android.musicplayer.action.PLAY";
    public static final String ACTION_PAUSE = "com.example.android.musicplayer.action.PAUSE";
    public static final String ACTION_STOP = "com.example.android.musicplayer.action.STOP";
    public static final String ACTION_SKIP = "com.example.android.musicplayer.action.SKIP";
    public static final String ACTION_REWIND = "com.example.android.musicplayer.action.REWIND";
    public static final String ACTION_URL = "com.example.android.musicplayer.action.URL";
   
	public static final String ACTION_READY = "com.example.android.musicplayer.action.READY";
	public static final String ACTION_QUERY = "com.example.android.musicplayer.action.QUERY";

	public static final String ACTION_URI = "com.example.android.musicplayer.action.URI";
	public static final String ACTION_SEEK = "com.example.android.musicplayer.action.Seek";
	public static final String ACTION_DISPLAY = "com.example.android.musicplayer.action.DISPLAY";
	public static final String ACTION_SHUFFLE = "com.example.android.musicplayer.action.SHUFFLE";
	public static final String ACTION_REPEAT = "com.example.android.musicplayer.action.REPEAT";
	public static final String ACTION_FORWARD = "com.example.android.musicplayer.action.FORWARD";
	public static final String ACTION_BACK = "com.example.android.musicplayer.action.BACK";
	
	MusicRetriever.Item playingItem;
	
    // The volume we set the media player to when we lose audio focus, but are allowed to reduce
    // the volume instead of stopping playback.
    public static final float DUCK_VOLUME = 0.1f;

    // our media player
    static MediaPlayer mPlayer = null;

	static boolean startingup = true;
	
	static boolean constructingPlaylist = false;

    // our AudioFocusHelper object, if it's available (it's available on SDK level >= 8)
    // If not available, this will be null. Always check for null before using!
    AudioFocusHelper mAudioFocusHelper = null;

    // indicates the state our service:
    enum State {
        Retrieving, // the MediaRetriever is retrieving music
        Stopped,    // media player is stopped and not prepared to play
        Preparing,  // media player is preparing...
        Playing,    // playback active (media player ready!). (but the media player may actually be
                    // paused in this state if we don't have audio focus. But we stay in this state
                    // so that we know we have to resume playback once we get focus back)
        Paused      // playback paused (media player ready!)
    };

    State mState = State.Retrieving;

    // if in Retrieving mode, this flag indicates whether we should start playing immediately
    // when we are ready or not.
    boolean mStartPlayingAfterRetrieve = false;

    // if mStartPlayingAfterRetrieve is true, this variable indicates the URL that we should
    // start playing when we are ready. If null, we should play a random song from the device
    Uri mWhatToPlayAfterRetrieve = null;

    enum PauseReason {
        UserRequest,  // paused by user request
        FocusLoss,    // paused because of audio focus loss
    };

    // why did we pause? (only relevant if mState == State.Paused)
    PauseReason mPauseReason = PauseReason.UserRequest;

    // do we have audio focus?
    enum AudioFocus {
        NoFocusNoDuck,    // we don't have audio focus, and can't duck
        NoFocusCanDuck,   // we don't have focus, but can play at a low volume ("ducking")
        Focused           // we have full audio focus
    }
    AudioFocus mAudioFocus = AudioFocus.NoFocusNoDuck;

    // title of the song we are currently playing
    String mSongTitle = "";
    // title of the artist we are currrently listening to
    String mArtistTitle = "";

    // whether the song we are playing is streaming from the network
    boolean mIsStreaming = false;

    // Wifi lock that we hold when streaming files from the internet, in order to prevent the
    // device from shutting off the Wifi radio
    WifiLock mWifiLock;

    // The ID we use for the notification (the onscreen alert that appears at the notification
    // area at the top of the screen as an icon -- and as text as well if the user expands the
    // notification area).
    final int NOTIFICATION_ID = 1;

    // Our instance of our MusicRetriever, which handles scanning for media and
    // providing titles and URIs as we need.
    static MusicRetriever mRetriever;

    // our RemoteControlClient object, which will use remote control APIs available in
    // SDK level >= 14, if they're available.
    RemoteControlClientCompat mRemoteControlClientCompat;

    // Dummy album art we will pass to the remote control (if the APIs are available).
    Bitmap mDummyAlbumArt;

    // The component name of MusicIntentReceiver, for use with media button and remote control
    // APIs
    ComponentName mMediaButtonReceiverComponent;

    AudioManager mAudioManager;
    NotificationManager mNotificationManager;

    Notification mNotification = null;

    /**
     * Makes sure the media player exists and has been reset. This will create the media player
     * if needed, or reset the existing media player if one already exists.
     */
    void createMediaPlayerIfNeeded() {
        if (mPlayer == null) {
            mPlayer = new MediaPlayer();

            // Make sure the media player will acquire a wake-lock while playing. If we don't do
            // that, the CPU might go to sleep while the song is playing, causing playback to stop.
            //
            // Remember that to use this, we have to declare the android.permission.WAKE_LOCK
            // permission in AndroidManifest.xml.
            mPlayer.setWakeMode(getApplicationContext(), PowerManager.PARTIAL_WAKE_LOCK);

            // we want the media player to notify us when it's ready preparing, and when it's done
            // playing:
            mPlayer.setOnPreparedListener(this);
            mPlayer.setOnCompletionListener(this);
            mPlayer.setOnErrorListener(this);
        }
        else
            mPlayer.reset();
    }

    @Override
    public void onCreate() {
        Log.i(TAG, "debug: Creating service");

        // Create the Wifi lock (this does not acquire the lock, this just creates it)
        mWifiLock = ((WifiManager) getSystemService(Context.WIFI_SERVICE))
                        .createWifiLock(WifiManager.WIFI_MODE_FULL, "mylock");

        mNotificationManager = (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        mAudioManager = (AudioManager) getSystemService(AUDIO_SERVICE);

        // Create the retriever and start an asynchronous task that will prepare it.
        mRetriever = new MusicRetriever(getContentResolver());
        (new PrepareMusicRetrieverTask(mRetriever,this)).execute();

        // create the Audio Focus Helper, if the Audio Focus feature is available (SDK 8 or above)
        if (android.os.Build.VERSION.SDK_INT >= 8)
            mAudioFocusHelper = new AudioFocusHelper(getApplicationContext(), this);
        else
            mAudioFocus = AudioFocus.Focused; // no focus feature, so we always "have" audio focus

        mDummyAlbumArt = BitmapFactory.decodeResource(getResources(), R.drawable.dummy_album_art);

        mMediaButtonReceiverComponent = new ComponentName(this, MusicIntentReceiver.class);
    }

    /**
     * Called when we receive an Intent. When we receive an intent sent to us via startService(),
     * this is the method that gets called. So here we react appropriately depending on the
     * Intent's action, which specifies what is being requested of us.
     */
    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        String action = intent.getAction();
		if (action.equals(ACTION_TOGGLE_PLAYBACK))
			processTogglePlaybackRequest();
		else if (action.equals(ACTION_READY))
			processReadyRequest();
		else if (action.equals(ACTION_PLAY))
			processPlayRequest();
		else if (action.equals(ACTION_PAUSE))
			processPauseRequest();
		else if (action.equals(ACTION_SKIP))
			processSkipRequest();
		else if (action.equals(ACTION_STOP))
			processStopRequest();
		else if (action.equals(ACTION_REWIND))
			processRewindRequest();
		else if (action.equals(ACTION_URL))
			processAddRequest(intent);
		else if (action.equals(ACTION_URI))
			processURIRequest(intent);
		else if (action.equals(ACTION_SHUFFLE))
			processShuffleRequest();
		else if (action.equals(ACTION_REPEAT))
			processRepeatRequest();
		else if (action.equals(ACTION_FORWARD))
			processFastForwardRequest();
		else if (action.equals(ACTION_BACK))
			processGoBackRequest();

        return START_NOT_STICKY; // Means we started the service, but don't want it to
                                 // restart in case it's killed.
    }
    
	private void processReadyRequest() {
		
		mWhatToPlayAfterRetrieve = null;
		mStartPlayingAfterRetrieve = false;
		tryToGetAudioFocus();
	}
	
    void processTogglePlaybackRequest() {
        if (mState == State.Paused || mState == State.Stopped) {
            processPlayRequest();
        } else {
            processPauseRequest();
        }
    }

    void processPlayRequest() {
        if (mState == State.Retrieving) {
            // If we are still retrieving media, just set the flag to start playing when we're
            // ready
            mWhatToPlayAfterRetrieve = null; // play a random song
            mStartPlayingAfterRetrieve = true;
            return;
        }

        tryToGetAudioFocus();

        // actually play the song

        if (mState == State.Stopped) {
            // If we're stopped, just go ahead to the next song and start playing
            playNextSong(null);
        }
        else if (mState == State.Paused) {
            // If we're paused, just continue playback and restore the 'foreground service' state.
            mState = State.Playing;
            setUpAsForeground(mSongTitle + " (playing)");
            configAndStartMediaPlayer();
        }

        // Tell any remote controls that our playback state is 'playing'.
        if (mRemoteControlClientCompat != null) {
            mRemoteControlClientCompat
                    .setPlaybackState(RemoteControlClient.PLAYSTATE_PLAYING);
        }
    }

    void processPauseRequest() {
        if (mState == State.Retrieving) {
            // If we are still retrieving media, clear the flag that indicates we should start
            // playing when we're ready
            mStartPlayingAfterRetrieve = false;
            return;
        }

        if (mState == State.Playing) {
            // Pause media player and cancel the 'foreground service' state.
            mState = State.Paused;
            mPlayer.pause();
            relaxResources(false); // while paused, we always retain the MediaPlayer
            // do not give up audio focus
        }

        // Tell any remote controls that our playback state is 'paused'.
        if (mRemoteControlClientCompat != null) {
            mRemoteControlClientCompat
                    .setPlaybackState(RemoteControlClient.PLAYSTATE_PAUSED);
        }
    }

    void processRewindRequest() {
        //if (mState == State.Playing || mState == State.Paused)
        //    mPlayer.seekTo(0);
    	if (mState == State.Playing || mState == State.Paused)
			tryToGetAudioFocus();
		playPreviousSong(null);
    }
    
    private void processAddRequest(Intent intent) {

		// user wants to play a song directly by URL or path. The URL or path
		// comes in the "data"
		// part of the Intent. This Intent is sent by {@link MainActivity} after
		// the user
		// specifies the URL/path via an alert box.
		if (mState == State.Retrieving) {
			// we'll play the requested URL right after we finish retrieving
			mWhatToPlayAfterRetrieve = intent.getData();
			mStartPlayingAfterRetrieve = true;
		} else if (mState == State.Playing || mState == State.Paused
				|| mState == State.Stopped) {
			Log.i(TAG, "Playing from URL/path: " + intent.getData().toString());
			System.out.println("Process Add Request "
					+ intent.getData().toString());
			tryToGetAudioFocus();
			playNextSong(intent.getData().toString());
		}
	}
	
	private void processURIRequest(Intent intent) {
		Bundle extras = intent.getExtras();
		int p = extras.getInt("Position");
		mStartPlayingAfterRetrieve = true;
		createMediaPlayerIfNeeded();
		mPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
		try {
			playGivenSong(p);
		} catch (Exception e) {
			e.printStackTrace();
		}
	}
	

	private void processShuffleRequest(){
		Item tempItem = null;
		try {
			tempItem = MusicRetriever.getCurrentSong();
		} catch (InterruptedException e1) {
		}
		System.out.println(MusicRetriever.mposition);
			MusicRetriever.shuffle = !MusicRetriever.shuffle;
			if (MusicRetriever.shuffle){
				if (MusicRetriever.usingGenPlaylist){
					MusicRetriever.ShuffleGeneratedPlaylist(MusicRetriever.mposition);
					MusicRetriever.mposition = -1;
					return;
				}
				if (MusicRetriever.mainSongList) {
					MusicRetriever.ShuffleOrderedSongs(MusicRetriever.mposition);
					MusicRetriever.mposition = -1;
					return;
				}
				if (MusicRetriever.usingPlaylist) {
					MusicRetriever.ShufflePlaylist(MusicRetriever.mposition);
					MusicRetriever.mposition = -1;
					return;
				} else {
					MusicRetriever.ShufflemItems(MusicRetriever.mposition);
					MusicRetriever.mposition = -1;
					return;
				}
			} else {
				if (MusicRetriever.usingGenPlaylist){
					int index = 0;
					try {
						for (Item item : MusicRetriever.getGeneratedPlaylist()){
							System.out.println(index + " " + item.getTitle() + " " + MusicRetriever.getCurrentSong().getTitle());
							if (item.equals(tempItem)){
								break;
							}
							index++;
						}
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
					MusicRetriever.mposition = index - 1;
					return;
				}
				if (MusicRetriever.mainSongList) {
					int index = 0;
					try {
						for (Item item : MusicRetriever.getOrderedSongs()){
							System.out.println(index + " " + item.getTitle() + " " + MusicRetriever.getCurrentSong().getTitle());
							if (item.equals(tempItem)){
								break;
							}
							index++;
						}
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
					MusicRetriever.mposition = index - 1;
					return;
				}
				if (MusicRetriever.usingPlaylist) {
					int index = 0;
					try {
						for (Item item : MusicRetriever.getPlaylist()){
							System.out.println(index + " " + item.getTitle() + " " + MusicRetriever.getCurrentSong().getTitle());
							if (item.equals(tempItem)){
								break;
							}
							index++;
						}
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
					MusicRetriever.mposition = index - 1;
					return;
				} else {
					int index = 0;
					try {
						for (Item item : MusicRetriever.getItems()){
							System.out.println(index + " " + item.getTitle() + " " + MusicRetriever.getCurrentSong().getTitle());
							if (item.equals(tempItem)){
								break;
							}
							index++;
						}
					} catch (InterruptedException e) {
						e.printStackTrace();
					}
					MusicRetriever.mposition = index - 1;
					return;
				}
			}
	}
	
	private void processRepeatRequest(){
		if (MusicRetriever.repeat){
			MusicRetriever.repeat = false;	
		}
		else{
			MusicRetriever.repeat = true;
			
		}
		
	}
	
	private void processFastForwardRequest(){
		mPlayer.seekTo(mPlayer.getCurrentPosition() + 3000);
	}
	
	private void processGoBackRequest(){
		System.out.println("Going back");
		mPlayer.seekTo(mPlayer.getCurrentPosition() - 4000);
	}
	
    void processSkipRequest() {
        if (mState == State.Playing || mState == State.Paused) {
            tryToGetAudioFocus();
        	try {
				playNextSong(null);
			} catch (IndexOutOfBoundsException e) {
			}
        }
    }

    void processStopRequest() {
        processStopRequest(false);
    }

    void processStopRequest(boolean force) {
        if (mState == State.Playing || mState == State.Paused || force) {
            mState = State.Stopped;

            // let go of all resources...
            relaxResources(true);
            giveUpAudioFocus();

            // Tell any remote controls that our playback state is 'paused'.
            if (mRemoteControlClientCompat != null) {
                mRemoteControlClientCompat
                        .setPlaybackState(RemoteControlClient.PLAYSTATE_STOPPED);
            }

            // service is no longer necessary. Will be started again if needed.
            stopSelf();
        }
    }

    /**
     * Releases resources used by the service for playback. This includes the "foreground service"
     * status and notification, the wake locks and possibly the MediaPlayer.
     *
     * @param releaseMediaPlayer Indicates whether the Media Player should also be released or not
     */
    void relaxResources(boolean releaseMediaPlayer) {
        // stop being a foreground service
        stopForeground(true);

        // stop and release the Media Player, if it's available
        if (releaseMediaPlayer && mPlayer != null) {
            mPlayer.reset();
            mPlayer.release();
            mPlayer = null;
        }

        // we can also release the Wifi lock, if we're holding it
        if (mWifiLock.isHeld()) mWifiLock.release();
    }

    void giveUpAudioFocus() {
        if (mAudioFocus == AudioFocus.Focused && mAudioFocusHelper != null
                                && mAudioFocusHelper.abandonFocus())
            mAudioFocus = AudioFocus.NoFocusNoDuck;
    }
    

    /**
     * Reconfigures MediaPlayer according to audio focus settings and starts/restarts it. This
     * method starts/restarts the MediaPlayer respecting the current audio focus state. So if
     * we have focus, it will play normally; if we don't have focus, it will either leave the
     * MediaPlayer paused or set it to a low volume, depending on what is allowed by the
     * current focus settings. This method assumes mPlayer != null, so if you are calling it,
     * you have to do so from a context where you are sure this is the case.
     */
    void configAndStartMediaPlayer() {
        if (mAudioFocus == AudioFocus.NoFocusNoDuck) {
            // If we don't have audio focus and can't duck, we have to pause, even if mState
            // is State.Playing. But we stay in the Playing state so that we know we have to resume
            // playback once we get the focus back.
            if (mPlayer.isPlaying()) mPlayer.pause();
            return;
        }
        else if (mAudioFocus == AudioFocus.NoFocusCanDuck)
            mPlayer.setVolume(DUCK_VOLUME, DUCK_VOLUME);  // we'll be relatively quiet
        else
            mPlayer.setVolume(1.0f, 1.0f); // we can be loud

        if (!mPlayer.isPlaying()) mPlayer.start();
    }

    void tryToGetAudioFocus() {
        if (mAudioFocus != AudioFocus.Focused && mAudioFocusHelper != null
                        && mAudioFocusHelper.requestFocus())
            mAudioFocus = AudioFocus.Focused;
    }

    /**
     * Starts playing the next song. If manualUrl is null, the next song will be randomly selected
     * from our Media Retriever (that is, it will be a random song in the user's device). If
     * manualUrl is non-null, then it specifies the URL or path to the song that will be played
     * next.
     */
    public synchronized void playNextSong(String manualUrl) {
		mState = State.Stopped;
		relaxResources(false); // release everything except MediaPlayer

		try {
			MusicRetriever.Item playingItem = null;
			if (manualUrl != null) {
				// set the source of the media player to a manual URL or path
				createMediaPlayerIfNeeded();
				mPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
				mPlayer.setDataSource(manualUrl);
				mIsStreaming = manualUrl.startsWith("http:")
						|| manualUrl.startsWith("https:");

				playingItem = new MusicRetriever.Item(0, null, manualUrl, null,
						0, 0);
			} else {
				mIsStreaming = false; // playing a locally available song
				try {
					if (startingup){
						playingItem = MusicRetriever.getCurrentSong();
						startingup = false;
					}
					else {
							playingItem = mRetriever.getNextItem();
					}
				} catch (Exception e) {
					playGivenSong(0);
					return;
				}
				
				// Here we are looping back to the start of the playlist
				
				if (playingItem == null) {
					if (MusicRetriever.repeat){
					playingItem = mRetriever.getGivenItem(0);
					}
					else {
						playingItem = mRetriever.getGivenItem(0);
						mPlayer.seekTo(0);
						mPlayer.stop();
						return;
					}
				}

				// set the source of the media player a a content URI
				createMediaPlayerIfNeeded();
				mPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
				try {
					mPlayer.setDataSource(getApplicationContext(),
							playingItem.getURI());
				} catch (IllegalStateException e) {
					System.out.println("Exception caught");
				}
				;
			}
			mSongTitle = playingItem.getTitle();
			mArtistTitle = playingItem.getArtist();

			mState = State.Preparing;
			setUpAsForeground(mSongTitle);

			// Use the media button APIs (if available) to register ourselves
			// for media button
			// events

			MediaButtonHelper.registerMediaButtonEventReceiverCompat(
					mAudioManager, mMediaButtonReceiverComponent);

			// Use the remote control APIs (if available) to set the playback
			// state

			if (mRemoteControlClientCompat == null) {
				Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
				intent.setComponent(mMediaButtonReceiverComponent);
				mRemoteControlClientCompat = new RemoteControlClientCompat(
						PendingIntent.getBroadcast(this /* context */, 0 /*
																		 * requestCode
																		 * ,
																		 * ignored
																		 */,
								intent /* intent */, 0 /* flags */));
				RemoteControlHelper.registerRemoteControlClient(mAudioManager,
						mRemoteControlClientCompat);
			}

			mRemoteControlClientCompat
					.setPlaybackState(RemoteControlClient.PLAYSTATE_PLAYING);

			mRemoteControlClientCompat
					.setTransportControlFlags(RemoteControlClient.FLAG_KEY_MEDIA_PLAY
							| RemoteControlClient.FLAG_KEY_MEDIA_PAUSE
							| RemoteControlClient.FLAG_KEY_MEDIA_NEXT
							| RemoteControlClient.FLAG_KEY_MEDIA_STOP);

			// Update the remote controls
			mRemoteControlClientCompat
					.editMetadata(true)
					.putString(MediaMetadataRetriever.METADATA_KEY_ARTIST,
							playingItem.getArtist())
					.putString(MediaMetadataRetriever.METADATA_KEY_ALBUM,
							playingItem.getAlbum())
					.putString(MediaMetadataRetriever.METADATA_KEY_TITLE,
							playingItem.getTitle())
					.putLong(MediaMetadataRetriever.METADATA_KEY_DURATION,
							playingItem.getDuration())
					.putBitmap(
							RemoteControlClientCompat.MetadataEditorCompat.METADATA_KEY_ARTWORK,
							MusicRetriever.getArtForDisplay()).apply();

			// starts preparing the media player in the background. When it's
			// done, it will call
			// our OnPreparedListener (that is, the onPrepared() method on this
			// class, since we set
			// the listener to 'this').
			//
			// Until the media player is prepared, we *cannot* call start() on
			// it!
			mPlayer.prepareAsync();

			// If we are streaming from the internet, we want to hold a Wifi
			// lock, which prevents
			// the Wifi radio from going to sleep while the song is playing. If,
			// on the other hand,
			// we are *not* streaming, we want to release the lock if we were
			// holding it before.
			if (mIsStreaming)
				mWifiLock.acquire();
			else if (mWifiLock.isHeld())
				mWifiLock.release();
		} catch (IOException ex) {
			Log.e("MusicService",
					"IOException playing next song: " + ex.getMessage());
			ex.printStackTrace();
		}
    }

    /**
	 * Starts playing the a given song. It takes an int provided in an intent's
	 * extras
	 */
	public synchronized void playGivenSong(int p) {
		mState = State.Stopped;
		relaxResources(false); // release everything except MediaPlayer
		MusicRetriever.Item playingItem = null;
		mIsStreaming = false; // playing a locally available song
		try {
			playingItem = mRetriever.getGivenItem(p);
			mPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
		} catch (Exception e) {
			mRetriever.getGivenItem(0);
		}
		try {
			mPlayer.setDataSource(getApplicationContext(), playingItem.getURI());
		} catch (Exception e) {
			
		}

		if (playingItem == null) {
			Toast.makeText(
					this,
					"No available music to play. Place some music on your external storage "
							+ "device (e.g. your SD card) and try again.",
					Toast.LENGTH_LONG).show();
			processStopRequest(true); // stop everything!
			return;
		}

		mSongTitle = playingItem.getTitle();
		mArtistTitle = playingItem.getArtist();

		mState = State.Preparing;
		setUpAsForeground(mSongTitle + " (loading)");

		// Use the media button APIs (if available) to register ourselves for
		// media button
		// events

		MediaButtonHelper.registerMediaButtonEventReceiverCompat(mAudioManager,
				mMediaButtonReceiverComponent);

		// Use the remote control APIs (if available) to set the playback state

		if (mRemoteControlClientCompat == null) {
			Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
			intent.setComponent(mMediaButtonReceiverComponent);
			mRemoteControlClientCompat = new RemoteControlClientCompat(
					PendingIntent.getBroadcast(this /* context */, 0 /*
																	 * requestCode,
																	 * ignored
																	 */,
							intent /* intent */, 0 /* flags */));
			RemoteControlHelper.registerRemoteControlClient(mAudioManager,
					mRemoteControlClientCompat);
		}

		mRemoteControlClientCompat
				.setPlaybackState(RemoteControlClient.PLAYSTATE_PLAYING);

		mRemoteControlClientCompat
				.setTransportControlFlags(RemoteControlClient.FLAG_KEY_MEDIA_PLAY
						| RemoteControlClient.FLAG_KEY_MEDIA_PAUSE
						| RemoteControlClient.FLAG_KEY_MEDIA_NEXT
						| RemoteControlClient.FLAG_KEY_MEDIA_STOP);

		// Update the remote controls
		try {
			mRemoteControlClientCompat
					.editMetadata(true)
					.putString(MediaMetadataRetriever.METADATA_KEY_ARTIST,
							playingItem.getArtist())
					.putString(MediaMetadataRetriever.METADATA_KEY_ALBUM,
							playingItem.getAlbum())
					.putString(MediaMetadataRetriever.METADATA_KEY_TITLE,
							playingItem.getTitle())
					.putLong(MediaMetadataRetriever.METADATA_KEY_DURATION,
							playingItem.getDuration())
					// TODO: fetch real item artwork
					.putBitmap(
							RemoteControlClientCompat.MetadataEditorCompat.METADATA_KEY_ARTWORK,
							MusicRetriever.getArtForDisplay()).apply();
		} catch (FileNotFoundException e) {
			// TODO Auto-generated catch block
			e.printStackTrace();
		}

		// starts preparing the media player in the background. When it's done,
		// it will call
		// our OnPreparedListener (that is, the onPrepared() method on this
		// class, since we set
		// the listener to 'this').
		//
		// Until the media player is prepared, we *cannot* call start() on it!
		try {
			mPlayer.prepareAsync();
		} catch (Exception e) {
		}
		// createMediaPlayerIfNeeded();
		// If we are streaming from the internet, we want to hold a Wifi lock,
		// which prevents
		// the Wifi radio from going to sleep while the song is playing. If, on
		// the other hand,
		// we are *not* streaming, we want to release the lock if we were
		// holding it before.
		if (mIsStreaming)
			mWifiLock.acquire();
		else if (mWifiLock.isHeld())
			mWifiLock.release();
	}

	public synchronized void playPreviousSong(String manualUrl) {
		mState = State.Stopped;
		relaxResources(false); // release everything except MediaPlayer

		try {
			MusicRetriever.Item playingItem = null;
			if (manualUrl != null) {
				// set the source of the media player to a manual URL or path
				createMediaPlayerIfNeeded();
				mPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
				mPlayer.setDataSource(manualUrl);
				mIsStreaming = manualUrl.startsWith("http:")
						|| manualUrl.startsWith("https:");

				playingItem = new MusicRetriever.Item(0, null, manualUrl, null,
						0, 0);
			} else {
				mIsStreaming = false; // playing a locally available song
					playingItem = mRetriever.getPreviousItem();
				if (playingItem == null) {
						if (!MusicRetriever.shuffle){
							System.out.println("Playing item is null");
							if (MusicRetriever.usingGenPlaylist){
								playingItem = mRetriever.getGivenItem(MusicRetriever.generatedPlaylist.size() - 1);
							}
							else if (MusicRetriever.usingPlaylist){
								playingItem = mRetriever.getGivenItem(MusicRetriever.playlist.size() - 1);
							}
							else if (MusicRetriever.usingGenPlaylist){
									playingItem = mRetriever.getGivenItem(MusicRetriever.generatedPlaylist.size() - 1);
								}
							else{
									playingItem = mRetriever.getGivenItem(MusicRetriever.songs.size() - 1);
								}
							
						    } else {
							if (MusicRetriever.usingGenPlaylist){
								playingItem = mRetriever.getGivenItem(MusicRetriever.ShuffledgeneratedPlaylist.size() - 1);
								}
							else if (MusicRetriever.usingPlaylist){
								playingItem = mRetriever.getGivenItem(MusicRetriever.Shuffledplaylist.size() - 1);
								}
							else if (MusicRetriever.usingGenPlaylist){
								playingItem = mRetriever.getGivenItem(MusicRetriever.ShuffledgeneratedPlaylist.size() - 1);
								}
							else{
								playingItem = mRetriever.getGivenItem(MusicRetriever.ShuffledmSongList.size() - 1);
								}
						    }
						} 

				// set the source of the media player a a content URI
				createMediaPlayerIfNeeded();
				mPlayer.setAudioStreamType(AudioManager.STREAM_MUSIC);
				mPlayer.setDataSource(getApplicationContext(),
						playingItem.getURI());
			}

			mSongTitle = playingItem.getTitle();
			mArtistTitle = playingItem.getArtist();

			mState = State.Preparing;
			setUpAsForeground(mSongTitle);

			MediaButtonHelper.registerMediaButtonEventReceiverCompat(
					mAudioManager, mMediaButtonReceiverComponent);

			if (mRemoteControlClientCompat == null) {
				Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
				intent.setComponent(mMediaButtonReceiverComponent);
				mRemoteControlClientCompat = new RemoteControlClientCompat(
						PendingIntent.getBroadcast(this /* context */, 0 /*
																		 * requestCode
																		 * ,
																		 * ignored
																		 */,
								intent /* intent */, 0 /* flags */));
				RemoteControlHelper.registerRemoteControlClient(mAudioManager,
						mRemoteControlClientCompat);
			}

			mRemoteControlClientCompat
					.setPlaybackState(RemoteControlClient.PLAYSTATE_PLAYING);

			mRemoteControlClientCompat
					.setTransportControlFlags(RemoteControlClient.FLAG_KEY_MEDIA_PLAY
							| RemoteControlClient.FLAG_KEY_MEDIA_PAUSE
							| RemoteControlClient.FLAG_KEY_MEDIA_NEXT
							| RemoteControlClient.FLAG_KEY_MEDIA_STOP);

			// Update the remote controls
			mRemoteControlClientCompat
					.editMetadata(true)
					.putString(MediaMetadataRetriever.METADATA_KEY_ARTIST,
							playingItem.getArtist())
					.putString(MediaMetadataRetriever.METADATA_KEY_ALBUM,
							playingItem.getAlbum())
					.putString(MediaMetadataRetriever.METADATA_KEY_TITLE,
							playingItem.getTitle())
					.putLong(MediaMetadataRetriever.METADATA_KEY_DURATION,
							playingItem.getDuration())
					// TODO: fetch real item artwork
					.putBitmap(
							RemoteControlClientCompat.MetadataEditorCompat.METADATA_KEY_ARTWORK,
							MusicRetriever.getArtForDisplay()).apply();

			mPlayer.prepareAsync();
			if (mIsStreaming)
				mWifiLock.acquire();
			else if (mWifiLock.isHeld())
				mWifiLock.release();
		} catch (IOException ex) {
			Log.e("MusicService",
					"IOException playing next song: " + ex.getMessage());
			ex.printStackTrace();
		}

	}

    /** Called when media player is done playing current song. */
	public synchronized void onCompletion(MediaPlayer player) {
		while (constructingPlaylist){
			//do nothing
			try {
				wait(1000);
			} catch (InterruptedException e) {
				e.printStackTrace();
			}
		}
		// The media player finished playing the current song, so we go ahead
		// and start the next.
		try {
			playNextSong(null);
		} catch (Exception e) {
			playGivenSong(0);
		}
	}

    /** Called when media player is done preparing. */
    public void onPrepared(MediaPlayer player) {
        // The media player is done preparing. That means we can start playing!
        mState = State.Playing;
        updateNotification(mSongTitle + " (playing)");
        configAndStartMediaPlayer();
    }

    /** Updates the notification. */
    void updateNotification(String text) {
        PendingIntent pi = PendingIntent.getActivity(getApplicationContext(), 0,
                new Intent(getApplicationContext(), MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT);
        mNotification.setLatestEventInfo(getApplicationContext(), "RandomMusicPlayer", text, pi);
        mNotificationManager.notify(NOTIFICATION_ID, mNotification);
    }

    /**
     * Configures service as a foreground service. A foreground service is a service that's doing
     * something the user is actively aware of (such as playing music), and must appear to the
     * user as a notification. That's why we create the notification here.
     */
	/**
	 * Configures service as a foreground service. A foreground service is a
	 * service that's doing something the user is actively aware of (such as
	 * playing music), and must appear to the user as a notification. That's why
	 * we create the notification here.
	 */
	void setUpAsForeground(String text) {
		Bitmap icon = null;
		PendingIntent pi = PendingIntent.getActivity(getApplicationContext(),
				0, new Intent(getApplicationContext(), MainActivity.class),
				PendingIntent.FLAG_UPDATE_CURRENT);
		mNotification = new Notification();
		mNotification.tickerText = text;
		mNotification.icon = R.drawable.ic_media_play;
		try {
			icon = MusicRetriever.getArtForDisplay();
			Bitmap scaled = Bitmap.createScaledBitmap(icon, 100, 100, true);
			mNotification.largeIcon = scaled;
		} catch (Exception e) {
			mNotification.largeIcon = null;
		}
		mNotification.flags |= Notification.FLAG_ONGOING_EVENT;
		try {
			mNotification.setLatestEventInfo(getApplicationContext(),
					MusicRetriever.getCurrentSong().getArtist(), text, pi);
		} catch (InterruptedException e) {
			e.printStackTrace();
		}
		startForeground(NOTIFICATION_ID, mNotification);
	}

    /**
     * Called when there's an error playing media. When this happens, the media player goes to
     * the Error state. We warn the user about the error and reset the media player.
     */
    public boolean onError(MediaPlayer mp, int what, int extra) {
    	try {
			playingItem = MusicRetriever.getCurrentSong();
		} catch (InterruptedException e2) {
			e2.printStackTrace();
		}
		mPlayer.reset();
		try {
			mPlayer.setDataSource(getApplicationContext(), playingItem.getURI());
		} catch (IllegalArgumentException e1) {
			e1.printStackTrace();
		} catch (SecurityException e1) {
			e1.printStackTrace();
		} catch (IllegalStateException e1) {
			e1.printStackTrace();
		} catch (Exception e1) {
			e1.printStackTrace();
		}


		mSongTitle = playingItem.getTitle();

		mState = State.Preparing;
		setUpAsForeground(mSongTitle);

		MediaButtonHelper.registerMediaButtonEventReceiverCompat(mAudioManager,
				mMediaButtonReceiverComponent);

		if (mRemoteControlClientCompat == null) {
			Intent intent = new Intent(Intent.ACTION_MEDIA_BUTTON);
			intent.setComponent(mMediaButtonReceiverComponent);
			mRemoteControlClientCompat = new RemoteControlClientCompat(
					PendingIntent.getBroadcast(this, 0 , intent , 0 ));
			RemoteControlHelper.registerRemoteControlClient(mAudioManager,
					mRemoteControlClientCompat);
		}

		mRemoteControlClientCompat
				.setPlaybackState(RemoteControlClient.PLAYSTATE_PLAYING);

		mRemoteControlClientCompat
				.setTransportControlFlags(RemoteControlClient.FLAG_KEY_MEDIA_PLAY
						| RemoteControlClient.FLAG_KEY_MEDIA_PAUSE
						| RemoteControlClient.FLAG_KEY_MEDIA_NEXT
						| RemoteControlClient.FLAG_KEY_MEDIA_STOP);

		try {
			mRemoteControlClientCompat
					.editMetadata(true)
					.putString(MediaMetadataRetriever.METADATA_KEY_ARTIST,
							playingItem.getArtist())
					.putString(MediaMetadataRetriever.METADATA_KEY_ALBUM,
							playingItem.getAlbum())
					.putString(MediaMetadataRetriever.METADATA_KEY_TITLE,
							playingItem.getTitle())
					.putLong(MediaMetadataRetriever.METADATA_KEY_DURATION,
							playingItem.getDuration())
					.putBitmap(
							RemoteControlClientCompat.MetadataEditorCompat.METADATA_KEY_ARTWORK,
							MusicRetriever.getArtForDisplay()).apply();
		} catch (FileNotFoundException e) {
			e.printStackTrace();
		}
		try {
		mPlayer.prepareAsync();
		} catch (Exception e) {
			playNextSong(null);
		}
		if (mIsStreaming)
			mWifiLock.acquire();
		else if (mWifiLock.isHeld())
			mWifiLock.release();

		Log.e(TAG,
				"Error: what=" + String.valueOf(what) + ", extra="
						+ String.valueOf(extra));

		return true; // true indicates we handled the error
    }

    public void onGainedAudioFocus() {
        Toast.makeText(getApplicationContext(), "gained audio focus.", Toast.LENGTH_SHORT).show();
        mAudioFocus = AudioFocus.Focused;

        // restart media player with new focus settings
        if (mState == State.Playing)
            configAndStartMediaPlayer();
    }

    public void onLostAudioFocus(boolean canDuck) {
        Toast.makeText(getApplicationContext(), "lost audio focus." + (canDuck ? "can duck" :
            "no duck"), Toast.LENGTH_SHORT).show();
        mAudioFocus = canDuck ? AudioFocus.NoFocusCanDuck : AudioFocus.NoFocusNoDuck;

        // start/restart/pause media player with new focus settings
        if (mPlayer != null && mPlayer.isPlaying())
            configAndStartMediaPlayer();
    }

    public void onMusicRetrieverPrepared() {
        // Done retrieving!
        mState = State.Stopped;

        // If the flag indicates we should start playing after retrieving, let's do that now.
        if (mStartPlayingAfterRetrieve) {
            tryToGetAudioFocus();
            playNextSong(mWhatToPlayAfterRetrieve == null ?
                    null : mWhatToPlayAfterRetrieve.toString());
        }
    }


    @Override
    public void onDestroy() {
        // Service is being killed, so make sure we release our resources
        mState = State.Stopped;
        relaxResources(true);
        giveUpAudioFocus();
    }

    @Override
    public IBinder onBind(Intent arg0) {
        return null;
    }
}
