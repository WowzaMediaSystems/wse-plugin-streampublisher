/*
 * This code and all components (c) Copyright 2006 - 2018, Wowza Media Systems, LLC. All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.streampublisher;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.wowza.wms.amf.AMFPacket;
import com.wowza.wms.application.IApplicationInstance;
import com.wowza.wms.logging.WMSLogger;
import com.wowza.wms.logging.WMSLoggerFactory;
import com.wowza.wms.logging.WMSLoggerIDs;
import com.wowza.wms.mediacaster.IMediaCaster;
import com.wowza.wms.mediacaster.IMediaCasterNotify2;
import com.wowza.wms.module.ModuleBase;
import com.wowza.wms.stream.IMediaStream;
import com.wowza.wms.stream.IMediaStreamActionNotify;
import com.wowza.wms.stream.IMediaStreamPlay;
import com.wowza.wms.stream.publish.PlaylistItem;
import com.wowza.wms.stream.publish.Stream;

public class ModuleLoopUntilLive extends ModuleBase
{
	
	private class StreamListener implements IMediaStreamActionNotify
	{
		public void onPublish(IMediaStream stream, final String streamName, boolean isRecord, boolean isAppend)
		{
			String[] names = liveStreamNames.split(",");
			for(String name : names)
			{
				if (name.trim().equalsIgnoreCase(streamName))
				{
					if (appInstance.getMediaCasterStreams().getMediaCaster(streamName) != null && handleMediaCasters)
					{
						logger.info(MODULE_NAME + ".StreamListener.onPublish Stream is MediaCaster. Swapping handled by MediaCasterListener.onStreamStart [" + streamName +"]", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
						return;
					}
					logger.info(MODULE_NAME + ".StreamListener.onPublish Swapping to live [" + streamName +"]", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
					appInstance.getVHost().getHandlerThreadPool().execute(new Runnable()
					{

						@Override
						public void run()
						{
							swapToLive(streamName);
						}
					});
					break;
				}
			}
		}

		public void onUnPublish(IMediaStream stream, String streamName, boolean isRecord, boolean isAppend)
		{
			String[] names = liveStreamNames.split(",");
			for(String name : names)
			{
				if (name.trim().equalsIgnoreCase(streamName))
				{
					if (appInstance.getMediaCasterStreams().getMediaCaster(streamName) != null && handleMediaCasters)
					{
						logger.info(MODULE_NAME + ".onUnPublish Stream is MediaCaster. Swapping handled by MediaCasterListener.onStreamStop [" + streamName +"]", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
						return;
					}
					logger.info(MODULE_NAME + ".StreamListener.onPublish Swapping to playlist [" + streamName +"]", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
					swapToPlaylist(streamName);
					break;
				}
			}
		}

		public void onPause(IMediaStream stream, boolean isPause, double location)
		{
		}

		public void onPlay(IMediaStream stream, String streamName, double playStart, double playLen, int playReset)
		{
		}

		public void onSeek(IMediaStream stream, double location)
		{
		}

		public void onStop(IMediaStream stream)
		{
		}
	}
	
	private class MediaCasterListener implements IMediaCasterNotify2
	{

		public void onMediaCasterCreate(IMediaCaster mediaCaster)
		{
		}

		public void onMediaCasterDestroy(IMediaCaster mediaCaster)
		{
		}

		public void onRegisterPlayer(IMediaCaster mediaCaster, IMediaStreamPlay player)
		{
		}

		public void onUnRegisterPlayer(IMediaCaster mediaCaster, IMediaStreamPlay player)
		{
		}

		public void onSetSourceStream(IMediaCaster mediaCaster, IMediaStream stream)
		{
		}

		public void onConnectStart(IMediaCaster mediaCaster)
		{
		}

		public void onConnectSuccess(IMediaCaster mediaCaster)
		{
		}

		public void onConnectFailure(IMediaCaster mediaCaster)
		{
		}

		public void onStreamStart(IMediaCaster mediaCaster)
		{
			final String streamName = mediaCaster.getStream().getName();
			String[] names = liveStreamNames.split(",");
			for(String name : names)
			{
				if (name.trim().equalsIgnoreCase(streamName))
				{
					logger.info(MODULE_NAME + ".MediaCasterListener.onStreamStop Swapping to live [" + streamName +"]", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
					appInstance.getVHost().getHandlerThreadPool().execute(new Runnable()
					{

						@Override
						public void run()
						{
							swapToLive(streamName);
						}
					});
					break;
				}
			}
		}

		public void onStreamStop(IMediaCaster mediaCaster)
		{
			String streamName = mediaCaster.getStream().getName();
			String[] names = liveStreamNames.split(",");
			for(String name : names)
			{
				if (name.trim().equalsIgnoreCase(streamName))
				{
					logger.info(MODULE_NAME + ".MediaCasterListener.onStreamStop Swapping to playlist [" + streamName +"]", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
					swapToPlaylist(streamName);
					break;
				}
			}
		}
	}

	public static String MODULE_NAME = "ModuleLoopUntilLive";
	public static final String PROP_NAME_PREFIX = "loopUntilLive";
	
	private WMSLogger logger;
	
	private IApplicationInstance appInstance;
	private String liveStreamNames ="myStream";
	private String outStreamNames = "Stream1";
	private boolean reloadEntirePlaylist = true;
	private boolean handleMediaCasters = true;
	private boolean waitForLiveAudio = true;
	private boolean waitForLiveVideo = true;
	private long waitForLiveTimeout = 10000;
	private Object lock = new Object();
	private IMediaStreamActionNotify actionNotify = new StreamListener();

	private Map<String, List<PlaylistItem>> playlists = new HashMap<String, List<PlaylistItem>>();
	private Map<String, Integer> playlistIndexes = new HashMap<String, Integer>();
	
	public void onAppStart(IApplicationInstance appInstance) {
		logger = WMSLoggerFactory.getLoggerObj(appInstance);
		
		init(appInstance);
		logger.info(MODULE_NAME + ".onAppStart: ["+appInstance.getContextStr()+"]: Build #5", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
	}
	
	public void init(IApplicationInstance appInstance)
	{
		this.appInstance = appInstance;
		appInstance.addMediaCasterListener(new MediaCasterListener());
		// old prop name
		this.liveStreamNames = appInstance.getProperties().getPropertyStr(PROP_NAME_PREFIX + "Stream", liveStreamNames);
		// new prop name
		this.liveStreamNames = appInstance.getProperties().getPropertyStr(PROP_NAME_PREFIX + "SourceStreams", liveStreamNames);
		// old prop name
		this.outStreamNames = appInstance.getProperties().getPropertyStr(PROP_NAME_PREFIX + "OutStream", outStreamNames);
		// new prop name
		this.outStreamNames = appInstance.getProperties().getPropertyStr(PROP_NAME_PREFIX + "OutputStreams", outStreamNames);
		this.reloadEntirePlaylist = appInstance.getProperties().getPropertyBoolean(PROP_NAME_PREFIX + "ReloadEntirePlaylist", reloadEntirePlaylist);
		this.handleMediaCasters = appInstance.getProperties().getPropertyBoolean(PROP_NAME_PREFIX + "HandleMediaCasters", handleMediaCasters);
		this.waitForLiveAudio = appInstance.getProperties().getPropertyBoolean(PROP_NAME_PREFIX + "WaitForLiveAudio", waitForLiveAudio);
		this.waitForLiveVideo = appInstance.getProperties().getPropertyBoolean(PROP_NAME_PREFIX + "WaitForLiveVideo", waitForLiveVideo);
		this.waitForLiveTimeout = appInstance.getProperties().getPropertyLong(PROP_NAME_PREFIX + "WaitForLiveTimeout", waitForLiveTimeout);
	}

	public void onStreamCreate(IMediaStream stream)
	{
		if(this.appInstance == null)
			init(stream.getStreams().getAppInstance());
		stream.addClientListener(actionNotify);
	}

	public void onStreamDestroy(IMediaStream stream)
	{
		stream.removeClientListener(actionNotify);
	}

	private void swapToLive(String streamName)
	{
		long startTime = System.currentTimeMillis();
		while (startTime + waitForLiveTimeout < System.currentTimeMillis())
		{
			IMediaStream liveStream = appInstance.getStreams().getStream(streamName);
			if (liveStream != null)
			{
				boolean ready = false;
				if(liveStream.isPublishStreamReady(waitForLiveAudio, waitForLiveVideo))
				{
					if(!waitForLiveAudio || !waitForLiveVideo)
						break;
					
					AMFPacket lastKeyFrame = liveStream.getLastKeyFrame();
					if(lastKeyFrame != null)
					{
						long lastKeyframeTC = lastKeyFrame.getAbsTimecode();
						List<AMFPacket> packets = liveStream.getPlayPackets();
						for(AMFPacket packet : packets)
						{
							if(packet.isAudio())
							{
								long audioTC = packet.getAbsTimecode();
								if(Math.abs(lastKeyframeTC - audioTC) < 50)
								{
									ready = true;
									break;
								}
							}
						}
					}
				}
				if(ready)
				{
					break;
				}
			}
			
			try
			{
				Thread.sleep(50);
			}
			catch (InterruptedException e)
			{
			}
		}
		
		// check to see if the live stream is still published.
		if(appInstance.getStreams().getStream(streamName) == null)
			return;
		
		String[] liveNames = liveStreamNames.split(",");
		String[] outNames = outStreamNames.split(",");
		int idx = 0;
		while (idx < liveNames.length)
		{
			String liveName = liveNames[idx].trim();
			if (streamName.equalsIgnoreCase(liveName))
			{
				if (outNames.length > idx)
				{
					String outName = outNames[idx].trim();

					Stream stream = (Stream)appInstance.getProperties().get(outName);
					if (stream != null)
					{
						synchronized(lock)
						{
							if(!playlists.containsKey(outName))
							{
								List<PlaylistItem> playlist = stream.getPlaylist();
								PlaylistItem currentItem = stream.getCurrentItem();
								int currentItemIndex = currentItem != null ? currentItem.getIndex() : 0;
								playlists.put(outName, playlist);
								playlistIndexes.put(outName, currentItemIndex);
								stream.play(liveName, -2, -1, true);
								logger.info(MODULE_NAME + ".swapToLive [" + stream.getName() + "]", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
							}
						}
					}
				}
			}
			idx++;
		}
	}
	
	private void swapToPlaylist(String streamName)
	{
		String[] liveNames = liveStreamNames.split(",");
		String[] outNames = outStreamNames.split(",");
		int idx = 0;
		
		while (idx < liveNames.length)
		{
			String liveName = liveNames[idx].trim();
			if(streamName.equalsIgnoreCase(liveName))
			{
				if(outNames.length > idx)
				{
					String outName = outNames[idx].trim();
					Stream stream = (Stream)appInstance.getProperties().get(outName);
					if(stream != null)
					{
						synchronized(lock)
						{
							List<PlaylistItem> playlist = playlists.remove(outName);
							Integer currentItemIndex = playlistIndexes.remove(outName);
							if(playlist != null)
							{
								if (reloadEntirePlaylist)
								{
									boolean reset = true;
									for (PlaylistItem item : playlist)
									{
										stream.play(item.getName(), item.getStart(), item.getLength(), reset);
										reset = false;
									}
									stream.play(currentItemIndex + 1);
								}
								else
								{
									if (playlist.size() > currentItemIndex)
									{
										PlaylistItem item = playlist.get(currentItemIndex);
										stream.play(item.getName(), item.getStart(), item.getLength(), true);
									}
								}
								logger.info(MODULE_NAME + ".swapToPlaylist [" + stream.getName() + "]", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
							}
						}
					}
				}
			}
			idx++;
		}
	}

	public String getLiveStreamNames()
	{
		return liveStreamNames;
	}

	public void setLiveStreamNames(String liveStreamNames)
	{
		this.liveStreamNames = liveStreamNames;
	}

	public String getOutStreamNames()
	{
		return outStreamNames;
	}

	public void setOutStreamNames(String outStreamNames)
	{
		this.outStreamNames = outStreamNames;
	}

	public boolean isReloadEntirePlaylist()
	{
		return reloadEntirePlaylist;
	}

	public void setReloadEntirePlaylist(boolean reloadEntirePlaylist)
	{
		this.reloadEntirePlaylist = reloadEntirePlaylist;
	}

	public boolean isHandleMediaCasters()
	{
		return handleMediaCasters;
	}

	public void setHandleMediaCasters(boolean handleMediaCasters)
	{
		this.handleMediaCasters = handleMediaCasters;
	}
}
