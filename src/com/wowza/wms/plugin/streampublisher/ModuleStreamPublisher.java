/*
 * This code and all components (c) Copyright 2006 - 2018, Wowza Media Systems, LLC. All rights reserved.
 * This code is licensed pursuant to the Wowza Public License version 1.0, available at www.wowza.com/legal.
 */
package com.wowza.wms.plugin.streampublisher;

import java.util.Map;

import com.wowza.wms.amf.*;
import com.wowza.wms.application.*;
import com.wowza.wms.client.*;
import com.wowza.wms.logging.WMSLogger;
import com.wowza.wms.logging.WMSLoggerFactory;
import com.wowza.wms.logging.WMSLoggerIDs;
import com.wowza.wms.module.*;
import com.wowza.wms.request.*;
import com.wowza.wms.server.Server;
import com.wowza.wms.stream.publish.Stream;

public class ModuleStreamPublisher extends ModuleBase
{
	public static final String MODULE_NAME = "ModuleStreamPublisher";
	public static final String PROP_NAME_PREFIX = "streamPublisher";
	
	private ServerListenerStreamPublisher streamPublisher;
	private IApplicationInstance appInstance;
	private WMSLogger logger;
	
	/**
	 * 
	 * Flash client loadSchedule method.
	 */
	public void loadSchedule(IClient client, RequestFunction function, AMFDataList params)
	{
		try
		{
			sendResult(client, params, loadSchedule());
		}
		catch (Exception e)
		{
			sendResult(client, params, e.getMessage());
		}
	}

	/**
	 * 
	 * Get the StreamPublisher and save it as a Server property if it doesn't already exist then load schedule.
	 */
	public void onAppStart(IApplicationInstance appInstance)
	{
		this.appInstance = appInstance;
		this.logger = WMSLoggerFactory.getLoggerObj(appInstance);
		logger.info(MODULE_NAME + ".onAppStart: ["+appInstance.getContextStr()+"]: Build #9", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
		
		streamPublisher = (ServerListenerStreamPublisher)Server.getInstance().getProperties().get(ServerListenerStreamPublisher.PROP_STREAMPUBLISHER);
		if(streamPublisher == null)
		{
			streamPublisher = new ServerListenerStreamPublisher();
			Server.getInstance().getProperties().setProperty(ServerListenerStreamPublisher.PROP_STREAMPUBLISHER, streamPublisher);
		}
		appInstance.getVHost().getThreadPool().execute(new Runnable() {

			@Override
			public void run()
			{
				try
				{
					String ret = loadSchedule();			
					logger.info(MODULE_NAME + ".onAppStart: ["+appInstance.getContextStr()+"]: "+ret, WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
				}
				catch (Exception e)
				{
					logger.error("ModuleStreamPublisher.onAppStart: ["+appInstance.getContextStr()+"]: " + e.getMessage(), e);
				}
			}
			
		});
	}

	/**
	 * 
	 * Stop streams and remove local reference to streamPublisher.
	 */
	public void onAppStop(IApplicationInstance appInstance)
	{
		unloadSchedule();
		streamPublisher = null;
	}
	
	/**
	 * public loadSchedule method.  Can be called from JMX.
	 * @throws Exception 
	 */
	public String loadSchedule() throws Exception
	{
		return streamPublisher.loadSchedule(appInstance);
	}
	
	/**
	 * public unloadSchedule method.  Can be called from JMX.
	 */
	@SuppressWarnings("unchecked")
	public void unloadSchedule()
	{
		Map<String, Stream> streams = (Map<String, Stream>)appInstance.getProperties().remove(PROP_NAME_PREFIX + "Streams");
		if(streams != null)
		{
			for(Stream stream : streams.values())
			{
				streamPublisher.shutdownStream(appInstance, stream);
			}
			streams.clear();
		}
		appInstance.getProperties().remove(PROP_NAME_PREFIX + "ScheduleLoaded");
	}

}
