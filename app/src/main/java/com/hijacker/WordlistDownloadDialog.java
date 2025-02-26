package com.hijacker;

/*
    Copyright (C) 2019  Christos Kyriakopoulos

    This program is free software: you can redistribute it and/or modify
    it under the terms of the GNU General Public License as published by
    the Free Software Foundation, either version 3 of the License, or
    (at your option) any later version.

    This program is distributed in the hope that it will be useful,
    but WITHOUT ANY WARRANTY; without even the implied warranty of
    MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
    GNU General Public License for more details.

    You should have received a copy of the GNU General Public License
    along with this program.  If not, see <http://www.gnu.org/licenses/>
 */

import static android.os.Environment.DIRECTORY_DOWNLOADS;

import android.Manifest;
import android.animation.Animator;
import android.animation.ObjectAnimator;
import android.annotation.SuppressLint;
import android.app.Dialog;
import android.app.DialogFragment;
import android.app.DownloadManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.DialogInterface;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.net.Uri;
import android.os.AsyncTask;
import android.os.Bundle;
import androidx.annotation.NonNull;
import androidx.core.content.ContextCompat;
import androidx.appcompat.app.AlertDialog;

import android.os.Environment;
import android.util.JsonReader;
import android.util.Log;
import android.view.View;
import android.view.ViewGroup;
import android.widget.AdapterView;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.InputStreamReader;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;

import javax.net.ssl.HttpsURLConnection;

import static com.hijacker.IsolatedFragment.is_ap;
import static com.hijacker.MainActivity.WORDLISTS_LINK;
import static com.hijacker.MainActivity.busybox;
import static com.hijacker.MainActivity.wl_path;
import static com.hijacker.Shell.getFreeShell;

public class WordlistDownloadDialog extends DialogFragment{
    View dialogView;
    ListView listView;
    ProgressBar progressBar;

    LoadTask task;
    WordlistAdapter adapter;
    ArrayList<Wordlist> wordlists = new ArrayList<>();

    @Override
    public Dialog onCreateDialog(Bundle savedInstanceState){
        AlertDialog.Builder builder = new AlertDialog.Builder(getActivity());
        dialogView = getActivity().getLayoutInflater().inflate(R.layout.wordlist_dialog, null);

        listView = dialogView.findViewById(R.id.wl_listview);
        progressBar = dialogView.findViewById(R.id.wl_pb);

        builder.setView(dialogView);
        builder.setTitle(R.string.wordlist_dialog_title);
        builder.setNeutralButton(R.string.cancel, new DialogInterface.OnClickListener() {
            @Override
            public void onClick(DialogInterface dialog, int which){}
        });

        adapter = new WordlistAdapter();
        listView.setAdapter(adapter);
        listView.setOnItemClickListener(new AdapterView.OnItemClickListener(){
            @Override
            public void onItemClick(AdapterView<?> adapterView, View view, int i, long l){
                beginDownload(wordlists.get(i));
                dismissAllowingStateLoss();
            }
        });


        task = new LoadTask();
        task.executeOnExecutor(AsyncTask.THREAD_POOL_EXECUTOR);

        return builder.create();
    }

    void beginDownload(Wordlist wl){
        //Check for external storage and internet permission
        if(ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.WRITE_EXTERNAL_STORAGE)!=PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(getActivity(), Manifest.permission.INTERNET)!=PackageManager.PERMISSION_GRANTED){
            Toast.makeText(getActivity(), getString(R.string.no_permissions), Toast.LENGTH_SHORT).show();
            return;
        }

        DownloadManager.Request request = new DownloadManager.Request(Uri.parse(wl.download_url));
        request.setTitle(wl.filename);
        request.allowScanningByMediaScanner();
        request.setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED);
        File AndroidDownloadDir= Environment.getExternalStoragePublicDirectory(DIRECTORY_DOWNLOADS);

        request.setDestinationUri(Uri.fromFile(new File(AndroidDownloadDir, wl.filename)));

        DownloadManager manager = (DownloadManager) getActivity().getSystemService(Context.DOWNLOAD_SERVICE);
        if(manager!=null){
            long downloadID=manager.enqueue(request);
            CrackFragment.downloadingTasks.put(downloadID,wl);
        }else{
            Toast.makeText(getActivity(), getString(R.string.cant_start_download), Toast.LENGTH_SHORT).show();
        }
    }

    class LoadTask extends AsyncTask<Void, String, Boolean>{
        @Override
        protected Boolean doInBackground(Void... params){
            try{
                HttpsURLConnection connection = (HttpsURLConnection) (new URL(WORDLISTS_LINK).openConnection());
                connection.setConnectTimeout(5000);
                connection.setReadTimeout(5000);

                JsonReader reader = new JsonReader(new InputStreamReader(connection.getInputStream()));
                reader.beginArray();
                if(!reader.hasNext()){
                    //No releases
                    Log.e("HIJACKER/WlLoadTask", "No files found");
                    return false;
                }

                //Run through all the objects in the files array
                while(reader.hasNext()){
                    reader.beginObject();

                    String filename = null, download_url = null;
                    int size = -1;
                    //Run through all the names in the 'file' object
                    while(reader.hasNext()){
                        String field = reader.nextName();
                        switch(field){
                            case "name":
                                filename = reader.nextString();
                                break;
                            case "size":
                                size = reader.nextInt();
                                break;
                            case "download_url":
                                download_url = reader.nextString();
                                break;
                            default:
                                reader.skipValue();
                                break;
                        }
                    }
                    reader.endObject();

                    wordlists.add(new Wordlist(filename, size, download_url));
                }
                reader.endArray();
                reader.close();
            }catch(Exception e){
                Log.e("HIJACKER/WlLoadTask", e.toString());
                return false;
            }

            return true;
        }
        @Override
        protected void onPostExecute(final Boolean success){
            ObjectAnimator pb_animator = ObjectAnimator.ofFloat(progressBar, "alpha", 1, 0);
            pb_animator.addListener(new Animator.AnimatorListener(){
                @Override
                public void onAnimationStart(Animator animator){}
                @Override
                public void onAnimationEnd(Animator animator){
                    progressBar.setIndeterminate(false);
                }
                @Override
                public void onAnimationCancel(Animator animator){}
                @Override
                public void onAnimationRepeat(Animator animator){}
            });
            pb_animator.start();

            adapter.notifyDataSetChanged();
            if(!success){
                if(((MainActivity)getActivity()).internetAvailable()){
                    Toast.makeText(getActivity(), getString(R.string.unknown_error), Toast.LENGTH_SHORT).show();
                }else{
                    Toast.makeText(getActivity(), getString(R.string.no_internet), Toast.LENGTH_SHORT).show();
                }
            }
        }
    }
    class WordlistAdapter extends ArrayAdapter<Tile>{
        WordlistAdapter(){
            super(WordlistDownloadDialog.this.getActivity(), R.layout.two_line_selectable_item);
        }

        @SuppressLint("SetTextI18n")
        @NonNull
        @Override
        public View getView(int position, View convertView, @NonNull ViewGroup parent){
            View itemview = convertView;
            if(itemview==null){
                itemview = getActivity().getLayoutInflater().inflate(R.layout.two_line_selectable_item, parent, false);
            }

            Wordlist current = wordlists.get(position);

            TextView main_tv = itemview.findViewById(R.id.main_text_view);
            TextView secondary_tv = itemview.findViewById(R.id.secondary_text_view);

            main_tv.setText(current.filename);
            secondary_tv.setText(getString(R.string.size) + ": " + current.size/1024 + "KB");

            return itemview;
        }

        @Override
        public int getCount(){
            return wordlists.size();
        }
    }
    protected class Wordlist{
        int size;
        String filename, download_url;
        Wordlist(String filename, int size, String url){
            this.filename = filename;
            this.size = size;
            this.download_url = url;
        }
    }
}
