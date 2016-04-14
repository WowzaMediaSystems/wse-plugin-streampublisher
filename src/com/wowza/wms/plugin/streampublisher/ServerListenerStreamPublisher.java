/*
 * This code and all components (c) Copyright 2006 - 2016, Wowza Media Systems, LLC. All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.streampublisher;

import java.io.File;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Timer;
import java.util.TimerTask;

import javax.xml.parsers.DocumentBuilder;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.w3c.dom.NodeList;

import com.wowza.util.StringUtils;
import com.wowza.wms.application.IApplication;
import com.wowza.wms.application.IApplicationInstance;
import com.wowza.wms.application.IApplicationInstanceNotify;
import com.wowza.wms.application.WMSProperties;
import com.wowza.wms.logging.WMSLogger;
import com.wowza.wms.logging.WMSLoggerFactory;
import com.wowza.wms.logging.WMSLoggerIDs;
import com.wowza.wms.server.IServer;
import com.wowza.wms.server.IServerNotify2;
import com.wowza.wms.server.Server;
import com.wowza.wms.stream.publish.IStreamActionNotify;
import com.wowza.wms.stream.publish.Playlist;
import com.wowza.wms.stream.publish.PlaylistItem;
import com.wowza.wms.stream.publish.Publisher;
import com.wowza.wms.stream.publish.Stream;
import com.wowza.wms.vhost.IVHost;
import com.wowza.wms.vhost.VHostSingleton;

public class ServerListenerStreamPublisher implements IServerNotify2
{

	private class ScheduledItem implements Comparable<ScheduledItem>
	{
		private IApplicationInstance appInstance;
		private Timer timer;
		private Date start;
		private Playlist playlist;
		private Stream stream;

		public ScheduledItem(IApplicationInstance appInstance, Date start, Playlist playlist, Stream stream)
		{
			this.appInstance = appInstance;
			this.start = start;
			this.playlist = playlist;
			this.stream = stream;
			timer = new Timer();
		}

		/**
		 * Start a timer to load the schedule when it triggers.  After it triggers, remove the schedule from the schedulesMap.
		 */
		public void start()
		{
			// Check to see if the stream is already running with a non-repeating schedule and set it to not unpublish if so.
			if (stream.getRepeat() == false)
			{
				logger.info(CLASS_NAME + " stream is set to not repeat, setUnpublishOnEnd: false " + stream.getName());
				stream.setUnpublishOnEnd(false);
			}
			else
				logger.info(CLASS_NAME + " stream is **NOT** set to not repeat, setUnpublishOnEnd: true " + stream.getName());

			timer.schedule(new TimerTask()
			{
				@Override
				public void run()
				{
					playlist.open(stream);
					logger.info(CLASS_NAME + " Scheduled stream is now live: " + stream.getName());
					removeFromList();
					timer = null;
				}
			}, start);
			logger.info(CLASS_NAME + " scheduled playlist: " + playlist.getName() + " on stream: " + stream.getName() + " for:" + start.toString());
		}

		/**
		 * Cancel the schedule and remove from schedulesMap.
		 */
		public void stop()
		{
			if (timer != null)
			{
				timer.cancel();
				logger.info(CLASS_NAME + " cancelled playlist: " + playlist.getName() + " on stream: " + stream.getName() + " for:" + start.toString());
				removeFromList();
				timer = null;
			}
		}

		/**
		 * Remove schedule from schedulesMap as it no longer needs to be referenced.
		 */
		private void removeFromList()
		{
			synchronized(lock)
			{
				Map<String, List<ScheduledItem>> schedulesMap = (Map<String, List<ScheduledItem>>)appInstance.getProperties().get(PROP_NAME_PREFIX + "Schedules");
				if (schedulesMap != null)
				{
					List<ScheduledItem> schedules = schedulesMap.get(stream.getName());
					if (schedules != null)
					{
						schedules.remove(this);
						boolean stopOnEnd = (playlist.getRepeat() == true || (schedules.isEmpty() && playlist.getRepeat() == false));
						stream.setUnpublishOnEnd(stopOnEnd);
						logger.info(CLASS_NAME + ".ScheduledItem.removeFromList(): setting stopOnEnd: " + stopOnEnd + ", playlist.getRepeat(): " + playlist.getRepeat() + ", schedules.isEmpty():" + schedules.isEmpty());
					}
				}
			}
		}

		@Override
		public int compareTo(ScheduledItem otherSchedule)
		{
			if(this.start.equals(otherSchedule.start))
				return 0;
			return this.start.before(otherSchedule.start) ? -1 : 1;
		}
		
	}

	private class StreamListener implements IStreamActionNotify
	{
		private IApplicationInstance appInstance;

		StreamListener(IApplicationInstance appInstance)
		{
			this.appInstance = appInstance;
		}

		@Override
		public void onPlaylistItemStart(Stream stream, PlaylistItem item)
		{
			try
			{
				String name = item.getName();
				if (appInstance.getProperties().getPropertyBoolean(PROP_NAME_PREFIX + "SendBroadcast", true))
					appInstance.broadcastMsg("PlaylistItemStart", name);
				if (stream.isSwitchLog())
					logger.info(CLASS_NAME + " PlayList Item Start: " + name);
			}
			catch (Exception ex)
			{
				logger.error(CLASS_NAME + " Get Item error: " + ex.getMessage());
			}
		}

		@Override
		public void onPlaylistItemStop(Stream stream, PlaylistItem item)
		{
			if (stream.getPlaylist().contains(item) && item.getIndex() == (stream.getPlaylist().size() - 1))
				if (!stream.getRepeat() && stream.isUnpublishOnEnd())  //  Stream will be unpublished and shut down.  Any future schedules will be canceled.
				{
					Map<String, Stream> streams = (Map<String, Stream>)appInstance.getProperties().get(PROP_NAME_PREFIX + "Streams");
					if (streams != null)
						streams.remove(stream.getName());
					shutdownStream(appInstance, stream);
					logger.info(CLASS_NAME + ": closing stream: " + stream.getName());
				}
		}
	}

	private class AppInstanceListener implements IApplicationInstanceNotify
	{

		@Override
		public void onApplicationInstanceCreate(IApplicationInstance appInstance)
		{
		}

		@Override
		public void onApplicationInstanceDestroy(IApplicationInstance appInstance)
		{
			WMSProperties props = appInstance.getProperties();
			Map<String, Stream> streams = (Map<String, Stream>)props.remove(PROP_NAME_PREFIX + "Streams");
			if (streams != null)
				for (Stream stream : streams.values())
					shutdownStream(appInstance, stream);
		}
	}

	public final static String CLASS_NAME = "ServerListenerStreamPublisher";
	public final static String PROP_NAME_PREFIX = "streamPublisher";
	public final static String PROP_STREAMPUBLISHER = "serverListenerStreamPublisher";

	private WMSLogger logger = WMSLoggerFactory.getLogger(null);
	private Object lock = new Object();

	@Override
	public void onServerCreate(IServer server)
	{
		server.getProperties().setProperty(PROP_STREAMPUBLISHER, this);
	}

	@Override
	public void onServerInit(IServer server)
	{
		logger.info(CLASS_NAME + " Started.");
		IVHost vhost = null;
		IApplication application = null;
		IApplicationInstance appInstance = null;
		String vhostName = server.getProperties().getPropertyStr("PublishToVHost", "_defaultVHost_"); // Old Prop Name
		vhostName = server.getProperties().getPropertyStr(PROP_NAME_PREFIX + "VHost", vhostName); // New Prop Name
		if (StringUtils.isEmpty(vhostName))
		{
			logger.info(CLASS_NAME + ": publishToVHost is empty. Can not run.");
			return;
		}
		try
		{
			vhost = VHostSingleton.getInstance(vhostName);
			if (vhost == null)
			{
				logger.warn(CLASS_NAME + ": Failed to get Vhost can not run.");
				return;
			}
		}
		catch (Exception e)
		{
			logger.error(CLASS_NAME + ": Failed to get Vhost can not run.", e);
			return;
		}
		String appContext = server.getProperties().getPropertyStr("PublishToApplication", "live/_definst_"); // Old Prop Name
		appContext = server.getProperties().getPropertyStr(PROP_NAME_PREFIX + "Application", appContext); // New Prop Name
		if (StringUtils.isEmpty(appContext))
		{
			logger.warn(CLASS_NAME + ": publishToApplication empty. Can not run.");
			return;
		}
		String[] appNameParts = appContext.split("/");
		String appName = appNameParts[0];
		String appInstName = appNameParts.length > 1 ? appNameParts[1] : IApplicationInstance.DEFAULT_APPINSTANCE_NAME;
		try
		{
			application = vhost.getApplication(appName);
			if (application == null)
			{
				logger.warn(CLASS_NAME + ": Failed to get Application can not run.");
				return;
			}
		}
		catch (Exception e)
		{
			logger.error(CLASS_NAME + ": Failed to get Application can not run.", e);
			return;
		}
		AppInstanceListener listener = (AppInstanceListener)application.getProperties().get(PROP_NAME_PREFIX + "AppInstanceListener");
		if (listener == null)
		{
			listener = new AppInstanceListener();
			application.addApplicationInstanceListener(listener);
			application.getProperties().setProperty(PROP_NAME_PREFIX + "AppInstanceListener", listener);
		}
		try
		{
			appInstance = application.getAppInstance(appInstName);
			if (appInstance == null)
			{
				logger.warn(CLASS_NAME + ": Failed to get Application Instance can not run.");
				return;
			}
		}
		catch (Exception e)
		{
			logger.error(CLASS_NAME + ": Failed to get Application Instance can not run.", e);
			return;
		}

		//  Module onAppStart runs as soon as getAppInstance() is called so check to see if the module loaded the schedule.
		if (appInstance.getProperties().getPropertyBoolean(PROP_NAME_PREFIX + "ScheduleLoaded", false))
			logger.info(CLASS_NAME + ": Schedule loaded by module.");
		else
			try
			{
				String ret = loadSchedule(appInstance);
				logger.info(CLASS_NAME + ": " + ret);
			}
			catch (Exception e)
			{
				logger.error(CLASS_NAME + ": " + e.getMessage(), e);
			}
	}

	public String loadSchedule(IApplicationInstance appInstance) throws Exception
	{
		WMSProperties serverProps = Server.getInstance().getProperties();
		WMSProperties props = appInstance.getProperties();
		synchronized(lock)
		{
			boolean switchLog = serverProps.getPropertyBoolean(PROP_NAME_PREFIX + "SwitchLog", true);
			switchLog = props.getPropertyBoolean(PROP_NAME_PREFIX + "SwitchLog", switchLog);
			boolean passMetaData = serverProps.getPropertyBoolean("PassthruMetaData", true); // Old Prop Name
			passMetaData = serverProps.getPropertyBoolean(PROP_NAME_PREFIX + "PassMetaData", passMetaData); // New Prop Name
			// Allow override in Application.xml
			passMetaData = props.getPropertyBoolean("PassthruMetaData", passMetaData); // Old Prop Name
			passMetaData = props.getPropertyBoolean(PROP_NAME_PREFIX + "PassMetaData", passMetaData); // New Prop Name
			// see if a schedule file is specified in the target application
			String scheduleSmil = serverProps.getPropertyStr(PROP_NAME_PREFIX + "SmilFile", "streamschedule.smil");
			scheduleSmil = props.getPropertyStr(PROP_NAME_PREFIX + "SmilFile", scheduleSmil);
			String storageDir = appInstance.getStreamStorageDir();
			try
			{
				String smilLoc = storageDir + "/" + scheduleSmil.replace("..", "");

				File playlistxml = new File(smilLoc);

				if (playlistxml.exists() == false)
					throw new Exception(CLASS_NAME + " Could not find playlist file: " + smilLoc);
				DocumentBuilderFactory dbf = DocumentBuilderFactory.newInstance();

				DocumentBuilder db = null;
				Document document = null;
				try
				{

					db = dbf.newDocumentBuilder();
					document = db.parse("file:///" + smilLoc);

				}
				catch (Exception e)
				{
					throw new Exception(CLASS_NAME + " Error parsing " + smilLoc + ". " + e.getMessage(), e);
				}

				document.getDocumentElement().normalize();

				NodeList streams = document.getElementsByTagName("stream");
				Map<String, Stream> streamsList = (Map<String, Stream>)props.getProperty(PROP_NAME_PREFIX + "Streams");
				if (streamsList == null)
				{
					streamsList = new HashMap<String, Stream>();
					props.setProperty(PROP_NAME_PREFIX + "Streams", streamsList);
				}
				Map<String, Stream> tmp = new HashMap<String, Stream>();
				tmp.putAll(streamsList);
				streamsList.clear();
				Map<String, List<ScheduledItem>> schedulesMap = (Map<String, List<ScheduledItem>>)props.getProperty(PROP_NAME_PREFIX + "Schedules");
				if (schedulesMap == null)
				{
					schedulesMap = new HashMap<String, List<ScheduledItem>>();
					props.setProperty(PROP_NAME_PREFIX + "Schedules", schedulesMap);
				}
				for (int i = 0; i < streams.getLength(); i++)
				{
					Node streamItem = streams.item(i);
					if (streamItem.getNodeType() == Node.ELEMENT_NODE)
					{
						Element e = (Element)streamItem;
						String streamName = e.getAttribute("name");

						logger.info(CLASS_NAME + ": Stream name is '" + streamName + "'");

						Stream stream = tmp.get(streamName);
						if (stream == null)
						{
							stream = Stream.createInstance(appInstance, streamName);
							if (stream == null)
							{
								logger.error(CLASS_NAME + " cannot create stream: " + streamName);
								continue;
							}
							IStreamActionNotify actionNotify = (IStreamActionNotify)props.get(PROP_NAME_PREFIX + "StreamListener");
							if (actionNotify == null)
							{
								actionNotify = new StreamListener(appInstance);
								props.setProperty(PROP_NAME_PREFIX + "StreamListener", actionNotify);
							}
							stream.addListener(actionNotify);
						}
						tmp.remove(streamName);
						streamsList.put(streamName, stream);
						props.setProperty(streamName, stream);  // Required for ModuleLoopUntilLive.
					}
				}

				//  Shut down any streams still in tmp as they are not in the new schedule.
				for (Stream stream : tmp.values())
					shutdownStream(appInstance, stream);
				tmp.clear();

				//  Iterate through the existing streams for the application and remove any existing schedules.
				for (Stream stream : streamsList.values())
				{
					synchronized(lock)
					{
						List<ScheduledItem> schedules = schedulesMap.remove(stream.getName());
						if (schedules != null)
						{
							for (ScheduledItem item : schedules)
								item.stop();
							schedules.clear();
						}
					}
				}

				NodeList playList = document.getElementsByTagName("playlist");
				if (playList.getLength() == 0)
					return "No playlists defined in smil file";
				
				synchronized(lock)
				{
					for (int i = 0; i < playList.getLength(); i++)
					{
						Node scheduledPlayList = playList.item(i);

						if (scheduledPlayList.getNodeType() == Node.ELEMENT_NODE)
						{
							Element e = (Element)scheduledPlayList;

							NodeList videos = e.getElementsByTagName("video");
							if (videos.getLength() == 0)
								return "No videos defined in stream";

							String streamName = e.getAttribute("playOnStream");
							if (streamName.length() == 0)
								continue;

							Playlist playlist = new Playlist(streamName);
							playlist.setRepeat((e.getAttribute("repeat").equals("false")) ? false : true);

							for (int j = 0; j < videos.getLength(); j++)
							{
								Node video = videos.item(j);
								if (video.getNodeType() == Node.ELEMENT_NODE)
								{
									Element e2 = (Element)video;
									String src = e2.getAttribute("src");
									Integer start = Integer.parseInt(e2.getAttribute("start"));
									Integer length = Integer.parseInt(e2.getAttribute("length"));
									// remove any prefix from live streams.
									if (start <= -2 && src.indexOf(":") != -1)
										src = src.substring(src.indexOf(":") + 1);
									playlist.addItem(src, start, length);
								}
							}
							String scheduled = e.getAttribute("scheduled");
							SimpleDateFormat parser = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss");
							Date startTime = null;
							try
							{
								startTime = parser.parse(scheduled);
								logger.info(CLASS_NAME + ".loadSchedule [scheduled: " + scheduled + ", startTime: " + startTime.toString() + ", time: " + startTime.getTime() + ", parser.timezone: " + parser.getTimeZone().getDisplayName() + "]", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
							}
							catch (Exception z)
							{
								throw new Exception(CLASS_NAME + " Parsing schedule time failed.", z);
							}
							Stream stream = streamsList.get(streamName);
							if (stream == null)
							{
								logger.warn(CLASS_NAME + " Stream does not exist for playlist: " + e.getAttribute("name") + " : " + streamName);
								continue;
							}

							List<ScheduledItem> schedules = schedulesMap.get(streamName);
							if (schedules == null)
							{
								schedules = new ArrayList<ScheduledItem>();
								schedulesMap.put(streamName, schedules);
							}
							stream.setSendOnMetadata(passMetaData);
							stream.setSwitchLog(switchLog);
							ScheduledItem schedule = new ScheduledItem(appInstance, startTime, playlist, stream);
							schedules.add(schedule);
							logger.info(CLASS_NAME + " Scheduled: " + stream.getName() + " for: " + scheduled);
						}
						
					}
					for(List<ScheduledItem> schedules : schedulesMap.values())
					{
						Collections.sort(schedules);
						for(ScheduledItem schedule : schedules)
							schedule.start();
					}
				}
			}
			catch (Exception ex)
			{
				throw new Exception(CLASS_NAME + " Error from playlist manager is '" + ex.getMessage() + "'", ex);
			}
			return "DONE!";
		}
	}

	public void shutdownStream(IApplicationInstance appInstance, Stream stream)
	{
		if (stream == null)
			return;

		synchronized(lock)
		{
			WMSProperties props = appInstance.getProperties();
			Map<String, List<ScheduledItem>> schedulesMap = (Map<String, List<ScheduledItem>>)props.get(PROP_NAME_PREFIX + "Schedules");
			if (schedulesMap != null)
			{
				List<ScheduledItem> schedules = schedulesMap.remove(stream.getName());
				if (schedules != null)
					for (ScheduledItem schedule : schedules)
						schedule.stop();
			}
			props.remove(stream.getName());
			stream.closeAndWait();
			Publisher publisher = stream.getPublisher();
			if (publisher != null)
			{
				publisher.unpublish();
				publisher.close();
			}
			logger.info(CLASS_NAME + ": Stream shut down : " + stream.getName());
		}
	}

	@Override
	public void onServerShutdownComplete(IServer server)
	{
	}

	@Override
	public void onServerShutdownStart(IServer server)
	{
	}

	@Override
	public void onServerConfigLoaded(IServer server)
	{
	}
}
