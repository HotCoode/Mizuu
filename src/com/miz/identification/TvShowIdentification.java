/*
 * Copyright (C) 2014 Michell Bak
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

package com.miz.identification;

import static com.miz.functions.PreferenceKeys.IGNORED_FILENAME_TAGS;
import static com.miz.functions.PreferenceKeys.LANGUAGE_PREFERENCE;

import java.io.File;
import java.util.ArrayList;

import com.google.common.collect.LinkedListMultimap;
import com.google.common.collect.Multimap;
import com.miz.db.DbAdapterTvShow;
import com.miz.db.DbAdapterTvShowEpisode;
import com.miz.functions.Episode;
import com.miz.functions.MizLib;
import com.miz.functions.TheTVDb;
import com.miz.functions.TvShowLibraryUpdateCallback;
import com.miz.functions.Tvshow;
import com.miz.mizuu.MizuuApplication;
import com.miz.mizuu.R;
import com.miz.widgets.ShowBackdropWidgetProvider;
import com.miz.widgets.ShowCoverWidgetProvider;
import com.miz.widgets.ShowStackWidgetProvider;

import android.appwidget.AppWidgetManager;
import android.content.ComponentName;
import android.content.Context;
import android.database.Cursor;
import android.preference.PreferenceManager;
import android.util.SparseBooleanArray;

public class TvShowIdentification {

	private final TvShowLibraryUpdateCallback mCallback;
	private final Context mContext;
	private final String mIgnoredTags;

	private ArrayList<ShowStructure> mShowStructures = new ArrayList<ShowStructure>();
	private Multimap<String, Integer> mShowFolderNameMap = LinkedListMultimap.create();
	private SparseBooleanArray mImdbMap = new SparseBooleanArray();

	private String mShowId = null, mLocale = null;
	private int mSeason = -1, mEpisode = -1;
	private boolean mCancel = false;

	public TvShowIdentification(Context context, TvShowLibraryUpdateCallback callback, ArrayList<ShowStructure> files) {
		mContext = context;
		mCallback = callback;
		mShowStructures = new ArrayList<ShowStructure>(files);

		mIgnoredTags = PreferenceManager.getDefaultSharedPreferences(mContext).getString(IGNORED_FILENAME_TAGS, "");

		// Get the language preference
		getLanguagePreference();
	}

	private void getLanguagePreference() {
		mLocale = PreferenceManager.getDefaultSharedPreferences(mContext).getString(LANGUAGE_PREFERENCE, "en");
	}

	/**
	 * Use this to disable TV show searching
	 * and attempt identification based on the
	 * provided TV show ID.
	 * @param showId
	 */
	public void setShowId(String showId) {
		mShowId = showId;
	}

	public boolean overrideShowId() {
		return null != getShowId();
	}

	public String getShowId() {
		return mShowId;
	}

	/**
	 * Use this to override the season
	 * and episode number of a given file.
	 * @param season
	 * @param episode
	 */
	public void setSeasonAndEpisode(int season, int episode) {
		mSeason = season;
		mEpisode = episode;
	}

	public int getSeason() {
		return mSeason;
	}

	public int getEpisode() {
		return mEpisode;
	}

	public boolean overrideSeasonAndEpisode() {
		return getSeason() >= 0 && getEpisode() >= 0;
	}

	/**
	 * Accepts two-letter ISO 639-1 language codes, i.e. "en".
	 * @param language
	 */
	public void setLanguage(String language) {
		mLocale = language;
	}

	public void cancel() {
		mCancel = true;
	}

	public void start() {
		// Go through all files
		for (int i = 0; i < mShowStructures.size(); i++) {
			if (mCancel)
				return;

			ShowStructure ss = mShowStructures.get(i);
			ss.setCustomTags(mIgnoredTags);

			mShowFolderNameMap.put(ss.getDecryptedShowFolderName(), i);
			mImdbMap.put(i, ss.hasImdbId());
		}

		// Make modular
		TheTVDb service = new TheTVDb(mContext);

		for (String showFolderName : mShowFolderNameMap.keySet()) {

			if (mCancel)
				return;

			Tvshow show = null;
			ArrayList<Tvshow> results = new ArrayList<Tvshow>();
			if (!overrideShowId()) {

				// Get the first ShowStructure element
				ShowStructure ss = mShowStructures.get(mShowFolderNameMap.get(showFolderName).iterator().next());

				// Check if there's an IMDb ID and attempt to search based on it
				if (ss.hasImdbId())
					results = service.searchForShowsByImdbId(ss.getImdbId(), "all");

				// If there's no results, attempt to search based on the show folder name and year
				if (results.size() == 0) {
					int year = mShowStructures.get(mShowFolderNameMap.get(showFolderName).iterator().next()).getReleaseYear();
					if (year >= 0)
						results = service.searchForShows(showFolderName + " " + year, "all");
				}

				// If there's still no results, search based on the show folder name only
				if (results.size() == 0)
					results = service.searchForShows(showFolderName, "all");
			} else
				show = service.getShow(getShowId(), mLocale);

			// Check if the show folder name results in any matches
			// - if it does, use that to identify all files
			if (results.size() > 0 || overrideShowId()) {

				// Get the TV show and create it in the database
				if (!overrideShowId())
					show = service.getShow(results.get(0).getId(), mLocale);
				createShow(show);

				int episodeCount = 0;
				for (Integer value : mShowFolderNameMap.get(showFolderName)) {
					if (mCancel)
						continue;

					ShowStructure ss = mShowStructures.get(value);
					for (com.miz.identification.Episode ep : ss.getEpisodes()) {
						if (mCancel)
							continue;

						downloadEpisode(show, ep.getSeason(), ep.getEpisode(), ss.getFilepath());
						episodeCount++;
					}
				}

				showAddedShowNotification(show, episodeCount);

			} else {
				// else go through each file and identify based on the filename

				for (Integer value : mShowFolderNameMap.get(showFolderName)) {
					if (mCancel)
						continue;

					ShowStructure ss = mShowStructures.get(value);

					show = null;
					results = new ArrayList<Tvshow>();

					// Check if there's an IMDb ID and attempt to search based on it
					if (ss.hasImdbId())
						results = service.searchForShowsByImdbId(ss.getImdbId(), "all");

					// If there's no results, attempt to search based on the show folder name and year
					if (results.size() == 0) {
						int year = ss.getReleaseYear();
						if (year >= 0)
							results = service.searchForShows(ss.getDecryptedFilename() + " " + year, "all");
					}

					// If there's still no results, search based on the show folder name only
					if (results.size() == 0)
						results = service.searchForShows(ss.getDecryptedFilename(), "all");

					if (results.size() == 0) {
						show = new Tvshow();
						show.setId("invalid");
					} else {					
						show = service.getShow(results.get(0).getId(), mLocale);
					}

					createShow(show);

					for (com.miz.identification.Episode ep : ss.getEpisodes()) {
						if (mCancel)
							continue;

						downloadEpisode(show, ep.getSeason(), ep.getEpisode(), ss.getFilepath());
					}

					showAddedShowNotification(show, ss.getEpisodes().size());
				}
			}
		}
	}

	private void showAddedShowNotification(Tvshow show, int episodeCount) {
		if (show == null)
			return;

		updateWidgets();

		File coverFile = MizLib.getTvShowThumb(mContext, show.getId());
		File backdropFile = MizLib.getTvShowBackdrop(mContext, show.getId());
		if (!backdropFile.exists())
			backdropFile = coverFile;

		mCallback.onTvShowAdded(show.getId(), show.getTitle(), MizLib.getNotificationImageThumbnail(mContext, coverFile.getAbsolutePath()),
				MizLib.decodeSampledBitmapFromFile(backdropFile.getAbsolutePath(), getNotificationImageWidth(), getNotificationImageHeight()), episodeCount);
	}

	private void createShow(Tvshow thisShow) {

		boolean downloadCovers = true;

		// Check if the show already exists before downloading the show info
		if (!thisShow.getId().equals("invalid") && !thisShow.getId().isEmpty()) {
			DbAdapterTvShow db = MizuuApplication.getTvDbAdapter();
			Cursor cursor = db.getShow(thisShow.getId());
			if (cursor.getCount() > 0) {
				downloadCovers = false;
			}
		}

		if (downloadCovers) {
			if (!thisShow.getId().equals("invalid") && !thisShow.getId().isEmpty()) {
				String thumb_filepath = MizLib.getTvShowThumb(mContext, thisShow.getId()).getAbsolutePath();
				String backdrop_filepath = MizLib.getTvShowBackdrop(mContext, thisShow.getId()).getAbsolutePath();

				// Download the cover file and try again if it fails
				if (!thisShow.getCover_url().isEmpty())
					if (!MizLib.downloadFile(thisShow.getCover_url(), thumb_filepath))
						MizLib.downloadFile(thisShow.getCover_url(), thumb_filepath);

				MizLib.resizeBitmapFileToCoverSize(mContext, thumb_filepath);

				// Download the backdrop image file and try again if it fails
				if (!thisShow.getBackdrop_url().isEmpty())
					if (!MizLib.downloadFile(thisShow.getBackdrop_url(), backdrop_filepath))
						MizLib.downloadFile(thisShow.getBackdrop_url(), backdrop_filepath);

				DbAdapterTvShow dbHelper = MizuuApplication.getTvDbAdapter();
				dbHelper.createShow(thisShow.getId(), thisShow.getTitle(), thisShow.getDescription(), thisShow.getActors(), thisShow.getGenre(),
						thisShow.getRating(), thisShow.getCertification(), thisShow.getRuntime(), thisShow.getFirst_aired(), "0");
			}
		}
	}

	private void downloadEpisode(Tvshow thisShow, int season, int episode, String filepath) {		
		Episode thisEpisode = new Episode();

		if (overrideSeasonAndEpisode()) {
			season = getSeason();
			episode = getEpisode();
		}

		ArrayList<Episode> episodes = thisShow.getEpisodes();
		int count = episodes.size();
		for (int i = 0; i < count; i++) {
			if (episodes.get(i).getSeason() == season && episodes.get(i).getEpisode() == episode) {
				thisEpisode = episodes.get(i);
				continue;
			}
		}

		if (thisEpisode.getEpisode() == -1) {
			thisEpisode.setEpisode(episode);
			thisEpisode.setSeason(season);
		}

		// Download the episode screenshot file and try again if it fails
		if (!thisEpisode.getScreenshot_url().isEmpty()) {
			String screenshotFile = MizLib.getTvShowEpisode(mContext, thisShow.getId(), season, episode).getAbsolutePath();
			if (!MizLib.downloadFile(thisEpisode.getScreenshot_url(), screenshotFile))
				MizLib.downloadFile(thisEpisode.getScreenshot_url(), screenshotFile);
		}

		// Download season cover if it hasn't already been downloaded
		if (thisShow.hasSeason(Integer.valueOf(thisEpisode.getSeason()))) {
			File seasonFile = MizLib.getTvShowSeason(mContext, thisShow.getId(), season);
			if (!seasonFile.exists()) {
				if (!MizLib.downloadFile(thisShow.getSeason(Integer.valueOf(thisEpisode.getSeason())).getCoverPath(), seasonFile.getAbsolutePath()))
					MizLib.downloadFile(thisShow.getSeason(Integer.valueOf(thisEpisode.getSeason())).getCoverPath(), seasonFile.getAbsolutePath());
			}
		}

		addToDatabase(thisShow, thisEpisode, filepath);
	}

	private void addToDatabase(Tvshow thisShow, Episode ep, String filepath) {
		DbAdapterTvShowEpisode dbHelper = MizuuApplication.getTvEpisodeDbAdapter();
		dbHelper.createEpisode(filepath, MizLib.addIndexZero(ep.getSeason()), MizLib.addIndexZero(ep.getEpisode()), thisShow.getId(), ep.getTitle(), ep.getDescription(),
				ep.getAirdate(), ep.getRating(), ep.getDirector(), ep.getWriter(), ep.getGueststars(), "0", "0");

		updateNotification(thisShow, ep, filepath);
	}

	private void updateNotification(Tvshow thisShow, Episode ep, String filepath) {
		File coverFile = MizLib.getTvShowThumb(mContext, thisShow.getId());
		File backdropFile = MizLib.getTvShowEpisode(mContext, thisShow.getId(), MizLib.addIndexZero(ep.getSeason()), MizLib.addIndexZero(ep.getEpisode()));
		if (!backdropFile.exists())
			backdropFile = coverFile;

		mCallback.onEpisodeAdded(thisShow.getId(), thisShow.getId().equals("invalid") ? filepath : thisShow.getTitle() + " S" + MizLib.addIndexZero(ep.getSeason()) + "E" + MizLib.addIndexZero(ep.getEpisode()),
				MizLib.getNotificationImageThumbnail(mContext, coverFile.getAbsolutePath()), MizLib.decodeSampledBitmapFromFile(backdropFile.getAbsolutePath(), getNotificationImageWidth(), getNotificationImageHeight()));
	}

	private void updateWidgets() {
		AppWidgetManager awm = AppWidgetManager.getInstance(mContext);
		awm.notifyAppWidgetViewDataChanged(awm.getAppWidgetIds(new ComponentName(mContext, ShowStackWidgetProvider.class)), R.id.stack_view); // Update stack view widget
		awm.notifyAppWidgetViewDataChanged(awm.getAppWidgetIds(new ComponentName(mContext, ShowCoverWidgetProvider.class)), R.id.widget_grid); // Update grid view widget
		awm.notifyAppWidgetViewDataChanged(awm.getAppWidgetIds(new ComponentName(mContext, ShowBackdropWidgetProvider.class)), R.id.widget_grid); // Update grid view widget
	}

	// These variables don't need to be re-initialized
	private int widgetWidth = 0, widgetHeight = 0;

	private int getNotificationImageWidth() {
		if (widgetWidth == 0)
			widgetWidth = MizLib.getLargeNotificationWidth(mContext);
		return widgetWidth;
	}

	private int getNotificationImageHeight() {
		if (widgetHeight == 0)
			widgetHeight = MizLib.getLargeNotificationWidth(mContext) / 2;
		return widgetHeight;
	}
}