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

import android.content.ContentResolver;
import android.content.ContentUris;
import android.database.Cursor;
import android.net.Uri;
import android.provider.MediaStore;
import android.util.Log;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.provider.BaseColumns;
import android.provider.MediaStore.Audio.AudioColumns;
import android.provider.MediaStore.MediaColumns;

import java.io.FileNotFoundException;
import java.io.InputStream;
import java.util.Collections;
import java.util.Comparator;

import com.example.android.musicplayer.MusicRetriever.Item;

/**
 * Retrieves and organizes media to play. Before being used, you must call {@link #prepare()},
 * which will retrieve all of the music on the user's device (by performing a query on a content
 * resolver). After that, it's ready to retrieve a random song, with its title and URI, upon
 * request.
 */
public class MusicRetriever {
    
	final String TAG = "MusicRetriever";
	static ContentResolver mContentResolver;
	// the items (songs) we have queried
	
	static List<Item> mItems = new ArrayList<Item>();
	static List<Item> mSongList = new ArrayList<Item>();
	static List<Item> playlist = new ArrayList<Item>();
	static List<Item> tempPlaylist = new ArrayList<Item>();
	public static List<Item> generatedPlaylist = new ArrayList<Item>();
	
	static List<Item> ShuffledmItems = new ArrayList<Item>();
	static List<Item> ShuffledmSongList = new ArrayList<Item>();
	static List<Item> Shuffledplaylist = new ArrayList<Item>();
	static List<Item> ShuffledgeneratedPlaylist = new ArrayList<Item>();
	
	static List<String> songs = new ArrayList<String>();
	static List<String> Orderedsongs = new ArrayList<String>();
	static List<String> artists = new ArrayList<String>();
	static List<String> albums = new ArrayList<String>();
	
	static Item tempItem;
	
	Random mRandom = new Random();
	static int mposition = 0;
	
	static boolean usingPlaylist = false;
	static boolean mainSongList = true;
	public static boolean usingGenPlaylist = false;

	static boolean shuffle = false;
	static boolean repeat = false;

    public MusicRetriever(ContentResolver cr) {
        mContentResolver = cr;
    }

    /**
     * Loads music data. This method may take long, so be sure to call it asynchronously without
     * blocking the main thread.
     */
    public void prepare() {
        Uri uri = android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI;
        Log.i(TAG, "Querying media...");
        Log.i(TAG, "URI: " + uri.toString());

        // Perform a query on the content resolver. The URI we're passing specifies that we
        // want to query for all audio media on external storage (e.g. SD card)
        Cursor cur = mContentResolver.query(uri, null,
                MediaStore.Audio.Media.IS_MUSIC + " = 1", null, null);
        Log.i(TAG, "Query finished. " + (cur == null ? "Returned NULL." : "Returned a cursor."));

        if (cur == null) {
            // Query failed...
            Log.e(TAG, "Failed to retrieve music: cursor is null :-(");
            return;
        }
        if (!cur.moveToFirst()) {
            // Nothing to query. There is no music on the device. How boring.
            Log.e(TAG, "Failed to move cursor to first row (no query results).");
            return;
        }

        Log.i(TAG, "Listing...");

        // retrieve the indices of the columns where the ID, title, etc. of the song are
        int artistColumn = cur.getColumnIndex(MediaStore.Audio.Media.ARTIST);
        int titleColumn = cur.getColumnIndex(MediaStore.Audio.Media.TITLE);
        int albumColumn = cur.getColumnIndex(MediaStore.Audio.Media.ALBUM);
        int durationColumn = cur.getColumnIndex(MediaStore.Audio.Media.DURATION);
        int idColumn = cur.getColumnIndex(MediaStore.Audio.Media._ID);
        int albumID = cur.getColumnIndex(AudioColumns.ALBUM_ID);

        Log.i(TAG, "Title column index: " + String.valueOf(titleColumn));
        Log.i(TAG, "ID column index: " + String.valueOf(titleColumn));

        // add each song to mItems
        do {
            Log.i(TAG, "ID: " + cur.getString(idColumn) + " Title: " + cur.getString(titleColumn));
            mItems.add(new Item(
                    cur.getLong(idColumn),
                    cur.getString(artistColumn),
                    cur.getString(titleColumn),
                    cur.getString(albumColumn),
                    cur.getLong(durationColumn),
                    cur.getLong(albumID)
                    ));
        } while (cur.moveToNext());

        Log.i(TAG, "Done querying media. MusicRetriever is ready.");
    }

    public ContentResolver getContentResolver() {
        return mContentResolver;
    }

    /** Returns a random Item. If there are no items available, returns null. */
    /*
    public Item getRandomItem() {
        if (mItems.size() <= 0) return null;
        return mItems.get(mRandom.nextInt(mItems.size()));
    }
    */
    
    /** Returns a random Item */
	public synchronized Item getRandomItem() {
		int Temp = 0;
			if (usingGenPlaylist){
				Temp = mRandom.nextInt(generatedPlaylist.size() - 1);
				mposition = Temp++;
				return generatedPlaylist.get(Temp);
			}
			if (mainSongList) {
				Temp = mRandom.nextInt(mSongList.size() - 1);
				mposition = Temp++;
				return mSongList.get(Temp);
			}
			if (usingPlaylist) {
				Temp = mRandom.nextInt(playlist.size()  - 1);
				mposition = Temp++;
				return playlist.get(Temp);
			} else {
				Temp = mRandom.nextInt(mItems.size() - 1);
				mposition = Temp++;
				return mItems.get(Temp);
			}

	}
	/** Returns the next Item. If there are no items available, returns null. */
	public synchronized Item getNextItem() {
		
		try {
			if (++mposition == mItems.size() - 1) {
				mposition = 1;
				return null;
			}
			if (shuffle){
				if (usingGenPlaylist){
					return ShuffledgeneratedPlaylist.get(mposition + 1);
				}
				if (mainSongList) {
					return ShuffledmSongList.get(mposition + 1);
				}
				if (usingPlaylist) {
					return Shuffledplaylist.get(mposition + 1);
				} else {
					return ShuffledmItems.get(mposition + 1);
				}
			}
			
			if (usingGenPlaylist){
				return generatedPlaylist.get(mposition + 1);
			}
			if (mainSongList) {
				return mSongList.get(mposition + 1);
			}
			if (usingPlaylist) {
				return playlist.get(mposition + 1);
			} else {
				return mItems.get(mposition + 1);
			}
		} catch (IndexOutOfBoundsException e) {
			mposition = 1;
			return null;
		}
	}

	public synchronized Item getGivenItem(int i) {
		System.out.println(usingGenPlaylist);
		mposition = i - 1;
		if (shuffle){
			if (usingGenPlaylist){
				return ShuffledgeneratedPlaylist.get(i);
			}
			if (mainSongList) {
				try {
				return ShuffledmSongList.get(i);
				} catch (Exception e) {
				}
			}
			if (usingPlaylist) {
				return Shuffledplaylist.get(i);
			} else {
				return ShuffledmItems.get(i);
			}
		}
		
		if (mainSongList) {
			try {
				return mSongList.get(i);
			} catch (Exception e) {
			}
		}
		if (usingGenPlaylist){
			return generatedPlaylist.get(i);
		}
		if (usingPlaylist) {
			return playlist.get(i);
			
		} else {
			try {
			return mItems.get(i);
			} catch (Exception e){
				return null;
			}
		}
	}

	public synchronized static Item getCurrentSong() throws InterruptedException {
		
		try {
			
			if (shuffle){
				if (usingGenPlaylist){
					return ShuffledgeneratedPlaylist.get(mposition + 1);
				}
				if (mainSongList) {
					return ShuffledmSongList.get(mposition + 1);
				}
				if (usingPlaylist) {
					return Shuffledplaylist.get(mposition + 1);
				} else {
					return ShuffledmItems.get(mposition + 1);
				}
			}	
		
		if (usingGenPlaylist){
			tempItem = generatedPlaylist.get(mposition + 1);
			return generatedPlaylist.get(mposition + 1);
		}
		} catch (Exception e){
			return tempItem;
		}
		
		if (mainSongList) {
			return mSongList.get(mposition + 1);
		}
		if (usingPlaylist) {
			try {
			return playlist.get(mposition + 1);
			} catch (IndexOutOfBoundsException e){
				Thread.sleep(1000);
				return playlist.get(mposition + 1);
			}
			
		} else {
			return mItems.get(mposition + 1);
		}
	}

	/**
	 * Returns the previous Item. If there are no items available, returns null.
	 */
	public Item getPreviousItem() {
		try {
			if (--mposition < -1) {
				mposition = 1;
				return null;
			}
			if (shuffle){
				if (usingGenPlaylist){
					return ShuffledgeneratedPlaylist.get(mposition + 1);
				}
				if (mainSongList) {
					return ShuffledmSongList.get(mposition + 1);
				}
				if (usingPlaylist) {
					return Shuffledplaylist.get(mposition + 1);
				} else {
					return ShuffledmItems.get(mposition + 1);
				}
			}
			if (usingGenPlaylist){
				return generatedPlaylist.get(mposition + 1);
			}
			if (mainSongList) {
				return mSongList.get(mposition + 1);
			}
			if (usingPlaylist) {
				return playlist.get(mposition + 1);
			} else {
				return mItems.get(mposition + 1);
			}
		} catch (IndexOutOfBoundsException e) {
			mposition = 1;
			return null;
		}
	}

	/** Fills in the songlist with every title in the song arraylist */
	@SuppressWarnings("unused")
	public void populate() throws FileNotFoundException {
		int i = 0;
		try {

			for (Item item : mItems) {
				songs.add(mItems.get(i).getTitle());

				if (!(artists.contains(mItems.get(i).getArtist()))) {
					artists.add(mItems.get(i).getArtist());
				}
				if (!(albums.contains(mItems.get(i).getAlbum()))) {
					albums.add(mItems.get(i).getAlbum());
					//albumsart.add(getArtwork(mItems.get(i).albumID));
				}
				i++;
			}
			mSongList.addAll(mItems);
			Collections.sort(mSongList, new Comparator<Object>() {

				public int compare(Object o1, Object o2) {
					Item i1 = (Item) o1;
					Item i2 = (Item) o2;
					return i1.getTitle().compareToIgnoreCase(i2.getTitle());
				}
			});
			Orderedsongs.addAll(songs);
			Collections.sort(Orderedsongs, new Comparator<Object>() {

				public int compare(Object o1, Object o2) {
					String i1 = (String) o1;
					String i2 = (String) o2;
					return i1.compareToIgnoreCase(i2);
				}
			});
			Collections.sort(albums, new Comparator<Object>() {

				public int compare(Object o1, Object o2) {
					String i1 = (String) o1;
					String i2 = (String) o2;
					return i1.compareToIgnoreCase(i2);
				}
			});
			Collections.sort(artists, new Comparator<Object>() {

				public int compare(Object o1, Object o2) {
					String i1 = (String) o1;
					String i2 = (String) o2;
					return i1.compareToIgnoreCase(i2);
				}
			});
			
			
			
		} catch (IndexOutOfBoundsException e) {
		}
	}

	public static List<String> getSongs() {
		return songs;
	}

	public static List<String> getOrdSongs() {
		return Orderedsongs;
	}

	public static List<String> getAlbums() {
		return albums;
	}

	public static List<String> getArtists() {
		return artists;
	}

	public static List<Item> getItemsForDisplay() {
		if (usingPlaylist) {
			return playlist;
		} else {
			return mItems;
		}
	}

	public synchronized static Bitmap getArtForDisplay() throws FileNotFoundException {
		
		if (shuffle){
			if (usingGenPlaylist){
				return getArtwork(ShuffledgeneratedPlaylist.get(mposition + 1).getAlbumID());
			}
			if (mainSongList) {
				return getArtwork(ShuffledmSongList.get(mposition + 1).getAlbumID());
			}
			if (usingPlaylist) {
				return getArtwork(Shuffledplaylist.get(mposition + 1).getAlbumID());
			} else {
				return getArtwork(ShuffledmItems.get(mposition + 1).getAlbumID());
			}
		}
		
		if (usingGenPlaylist){
			return getArtwork(generatedPlaylist.get(mposition + 1).getAlbumID());
		}
		if (mainSongList) {
			return getArtwork(mSongList.get(mposition + 1).getAlbumID());
		}
		if (usingPlaylist) {
			return getArtwork(playlist.get(mposition + 1).getAlbumID());
		} else {
			return getArtwork(mItems.get(mposition + 1).getAlbumID());
		}
	}
	

	public static List<Item> getItems() {
		return mItems;
	}

	public static List<Item> getPlaylist() {
		return playlist;
	}
	
	public static List<Item> getGeneratedPlaylist() {
		return generatedPlaylist;
	}

	public static List<Item> getOrderedSongs() {
		return mSongList;
	}
	
	
	public static List<Item> ShufflemItems(int i) {
		i++;
		ShuffledmItems.clear();
		Item temp = mItems.get(i);
		mItems.remove(i);
		ShuffledmItems.addAll(mItems);
		mItems.add(i, temp);
		Collections.shuffle(ShuffledmItems);
		ShuffledmItems.add(0 ,temp);
		return ShuffledmItems;
	}

	public static List<Item> ShufflePlaylist(int i) {
		i++;
		Shuffledplaylist.clear();
		Item temp = playlist.get(i);
		playlist.remove(i);
		Shuffledplaylist.addAll(playlist);
		playlist.add(i, temp);
		Collections.shuffle(Shuffledplaylist);
		Shuffledplaylist.add(0 ,temp);
		return Shuffledplaylist;
	}
	
	public static List<Item> ShuffleGeneratedPlaylist(int i) {
		i++;
		ShuffledgeneratedPlaylist.clear();
		Item temp = generatedPlaylist.get(i);
		generatedPlaylist.remove(i);
		ShuffledgeneratedPlaylist.addAll(generatedPlaylist);
		generatedPlaylist.add(i, temp);
		Collections.shuffle(ShuffledgeneratedPlaylist);
		ShuffledgeneratedPlaylist.add(0, temp);
		return ShuffledgeneratedPlaylist;
	}

	public static List<Item> ShuffleOrderedSongs(int i) {
		i++;
		ShuffledmSongList.clear();
		Item temp = mSongList.get(i);
		mSongList.remove(i);
		ShuffledmSongList.addAll(mSongList);
		mSongList.add(i, temp);
		Collections.shuffle(ShuffledmSongList);
		ShuffledmSongList.add(0 ,temp);
		return ShuffledmSongList;
	}
	

	public synchronized static Bitmap getArtwork(long albumID) {

		try {
			Uri sArtworkUri = Uri
					.parse("content://media/external/audio/albumart");
			Uri uri = ContentUris.withAppendedId(sArtworkUri, albumID);
			InputStream in = mContentResolver.openInputStream(uri);
			BitmapFactory.Options options=new BitmapFactory.Options();
			options.inPurgeable = true;
			Bitmap artwork = BitmapFactory.decodeStream(in, null, options);
			return artwork;
		} catch (Exception e) {
			Bitmap artwork = null;
			return artwork;
		}

	}
	
	public static List<String> getCurrentPlaylist(){
		List<String> CurrentPlaylist = new ArrayList<String>();
		if (usingGenPlaylist){
			for (Item item : generatedPlaylist) {
				CurrentPlaylist.add(item.getTitle());
			}
			return CurrentPlaylist;
		}
		if (mainSongList) {
			for (Item item : mSongList) {
				CurrentPlaylist.add(item.getTitle());
			}
			return CurrentPlaylist;
			
		}
		if (usingPlaylist) {
				for (Item item : playlist) {
					CurrentPlaylist.add(item.getTitle());
					
			}
				return CurrentPlaylist;
			
		} else {
				for (Item item : mItems) {
				CurrentPlaylist.add(item.getTitle());
			}
				return CurrentPlaylist;
		}
	}

	public static List<String> getAlbumsByArtist(String artist) {
		List<String> AlbumsByArtist = new ArrayList<String>();
		for (Item item : mItems) {
			if (item.getArtist().equals(artist)) {
				if (!(AlbumsByArtist.contains(item.getAlbum()))) {
					AlbumsByArtist.add(item.getAlbum());
				}
			}
		}
		AlbumsByArtist.add("All Songs");
		return AlbumsByArtist;
	}

	public static List<String> getSongsByAlbum(String album) {
		tempPlaylist.clear();
		List<String> SongsByAlbum = new ArrayList<String>();
		for (Item item : mItems) {
			if (item.getAlbum().equals(album)) {
				SongsByAlbum.add(item.getTitle());
				tempPlaylist.add(item);
			}
		}
		return SongsByAlbum;
	}


	public static List<String> getSongsByArtist(String artist) {

		tempPlaylist.clear();
		List<String> SongsByArtist = new ArrayList<String>();
		for (Item item : mItems) {
			if (item.getArtist().equals(artist)) {
				System.out.println(item.getTitle());
				SongsByArtist.add(item.getTitle());
				tempPlaylist.add(item);
			}
		}

		return SongsByArtist;
	}
	
    public static class Item {
        long id;
        String artist;
        String title;
        String album;
        long duration;
        long albumID;

        public Item(long id, String artist, String title, String album, long duration, long albumID) {
            this.id = id;
            this.artist = artist;
            this.title = title;
            this.album = album;
            this.duration = duration;
            this.albumID = albumID;
        }

        public long getId() {
            return id;
        }

        public String getArtist() {
            return artist;
        }

        public String getTitle() {
            return title;
        }

        public String getAlbum() {
            return album;
        }

        public long getDuration() {
            return duration;
        }
        
        public long getAlbumID() {
        	return albumID;
        }
        public Uri getURI() {
            /*return ContentUris.withAppendedId(
                    android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, id);*/
            System.out.println(ContentUris.withAppendedId(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,id).toString());
			if (ContentUris.withAppendedId(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,id) == null){
				return ContentUris.withAppendedId(android.provider.MediaStore.Audio.Media.INTERNAL_CONTENT_URI,id);
			}
			return ContentUris.withAppendedId(android.provider.MediaStore.Audio.Media.EXTERNAL_CONTENT_URI,id);
		
        }
    }
    public static String getSongs(int i) {
		return songs.get(i);
    }
}
