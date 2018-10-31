package com.wowza.wms.plugin.streampublisher;

import java.io.*;
import java.util.Map;

import com.wowza.util.HTTPUtils;
import com.wowza.util.StringUtils;
import com.wowza.wms.application.ApplicationInstance;
import com.wowza.wms.application.IApplication;
import com.wowza.wms.application.IApplicationInstance;
import com.wowza.wms.http.*;
import com.wowza.wms.logging.*;
import com.wowza.wms.server.Server;
import com.wowza.wms.stream.publish.Stream;
import com.wowza.wms.vhost.*;

public class HttpProviderStreamPublisherControl extends HTTProvider2Base
{

	public void onHTTPRequest(IVHost vhost, IHTTPRequest req, IHTTPResponse resp)
	{
		if (!doHTTPAuthentication(vhost, req, resp))
			return;

		Server server = Server.getInstance();
		
		ServerListenerStreamPublisher streamPublisher = (ServerListenerStreamPublisher)server.getProperties().getProperty(ServerListenerStreamPublisher.PROP_STREAMPUBLISHER);
		if(streamPublisher == null)
		{
			streamPublisher = new ServerListenerStreamPublisher();
			server.getProperties().setProperty(ServerListenerStreamPublisher.PROP_STREAMPUBLISHER, streamPublisher);
		}
			
		String queryStr = req.getQueryString();
		if (queryStr == null)
		{
			WMSLoggerFactory.getLogger(HTTPProviderMediaList.class).warn("HttpProviderStreamPublisherControl.onHTTPRequest: Query string missing");
			resp.setResponseCode(400);
			return;
		}

        Map<String, String> queryMap = HTTPUtils.splitQueryStr(queryStr);

        String appName = null;
        String appInstName = IApplicationInstance.DEFAULT_APPINSTANCE_NAME;
		String appContext = server.getProperties().getPropertyStr(ServerListenerStreamPublisher.PROP_NAME_PREFIX + "Application");
		if (!StringUtils.isEmpty(appContext))
		{
			String[] appNameParts = appContext.split("/");
			appName = appNameParts[0];
			appInstName = appNameParts.length > 1 ? appNameParts[1] : IApplicationInstance.DEFAULT_APPINSTANCE_NAME;
		}
		String action = "reloadSchedule";
		
		if(queryMap.containsKey("appName"))
		{
			appName = queryMap.get("appName");
			appInstName = IApplicationInstance.DEFAULT_APPINSTANCE_NAME;
		}
		
		if(StringUtils.isEmpty(appName))
		{
			WMSLoggerFactory.getLogger(HTTPProviderMediaList.class).warn("HttpProviderStreamPublisherControl.onHTTPRequest: appName is missing");
			resp.setResponseCode(400);
			return;
		}
		
		if(queryMap.containsKey("appInstName"))
		{
			appInstName = queryMap.get("appInstName");
		}
		
		appContext = appName + "/" + appInstName;
		
		if(queryMap.containsKey("action"))
		{
			action = queryMap.get("action");
		}
		
		boolean appInstanceAlreadyRunning = false;
		IApplication application = null;
		IApplicationInstance appInstance = null;
		String ret = "";
		
		if(vhost.applicationExists(appName))
		{
			application = vhost.getApplication(appName);
		}
		if(application == null)
		{
			WMSLoggerFactory.getLogger(HTTPProviderMediaList.class).warn("HttpProviderStreamPublisherControl.onHTTPRequest: [" + appContext + "] doesn't exist");
			resp.setResponseCode(404);
			return;
		}
		
		if(application.isAppInstanceLoaded(appInstName))
		{
			appInstanceAlreadyRunning = true;
		}
		
		boolean canLoadSchedule = false;
		while(true)
		{
			if(action.equalsIgnoreCase("loadSchedule"))
			{
				canLoadSchedule = true;
				break;
			}
			if(action.equalsIgnoreCase("reloadSchedule"))
			{
				if(!appInstanceAlreadyRunning)
				{
					canLoadSchedule = false;
					ret = "appInstance isn't running";
					break;
				}
				appInstance = application.getAppInstance(appInstName);
				if(appInstance == null)
				{
					WMSLoggerFactory.getLogger(HTTPProviderMediaList.class).warn("HttpProviderStreamPublisherControl.onHTTPRequest: [" + appContext + "] doesn't exist");
					resp.setResponseCode(404);
					return;
				}
				else
				{
					canLoadSchedule = appInstance.getProperties().getPropertyBoolean(ServerListenerStreamPublisher.PROP_NAME_PREFIX + "ScheduleLoaded", false);
					if(!canLoadSchedule)
						ret = "appInstance doesn't have a running schedule to reload";
				}
			}
			break;
		}
		
		if(action.equalsIgnoreCase("unLoadSchedule"))
		{
			if(appInstanceAlreadyRunning)
			{
				appInstance = application.getAppInstance(appInstName);
				if(appInstance == null)
				{
					WMSLoggerFactory.getLogger(HTTPProviderMediaList.class).warn("HttpProviderStreamPublisherControl.onHTTPRequest: [" + appContext + "] doesn't exist");
					resp.setResponseCode(404);
					return;
				}

				@SuppressWarnings("unchecked")
				Map<String, Stream> streams = (Map<String, Stream>)appInstance.getProperties().remove(ServerListenerStreamPublisher.PROP_NAME_PREFIX + "Streams");
				if(streams != null)
				{
					for(Stream stream : streams.values())
					{
						streamPublisher.shutdownStream(appInstance, stream);
					}
				}
				appInstance.getProperties().remove(ServerListenerStreamPublisher.PROP_NAME_PREFIX + "ScheduleLoaded");
				ret = "schedule unloaded";
				// AppInstances will stay loaded until there is at least 1 valid connection. 
				// Increment the connection count to allow the appInstnace to shut down if there are no other connection attempts.
				if(appInstance.getClientCountTotal() <= 0)
				{
					appInstance.incClientCountTotal();
					((ApplicationInstance)appInstance).setClientRemoveTime(System.currentTimeMillis());
				}
			}
			else
			{
				ret = "appInstance isn't running";
			}
		}
		else if(canLoadSchedule)
		{
			boolean scheduleLoaded = false;
			appInstance = application.getAppInstance(appInstName);
			if(appInstance == null)
			{
				WMSLoggerFactory.getLogger(HTTPProviderMediaList.class).warn("HttpProviderStreamPublisherControl.onHTTPRequest: [" + appContext + "] doesn't exist");
				resp.setResponseCode(404);
				return;
			}
			
			if(!appInstanceAlreadyRunning)
				scheduleLoaded = appInstance.getProperties().getPropertyBoolean(ServerListenerStreamPublisher.PROP_NAME_PREFIX + "ScheduleLoaded", false);
		
			if(!scheduleLoaded)
			{
				try
				{
					ret = streamPublisher.loadSchedule(appInstance);
				}
				catch (Exception e)
				{
					WMSLoggerFactory.getLogger(HTTPProviderMediaList.class).error("HttpProviderStreamPublisherControl.onHTTPRequest: [" + appContext + "] error loading schedule " + e.getMessage(), e);
					resp.setResponseCode(501);
					ret = "Error loading schedule " + e.getMessage();
				}
			}
			else
			{
				ret = "schedule loaded by appInstance restart";
			}
		}
		else if(StringUtils.isEmpty(ret))
			ret = "unreconised action";
		
		String retStr = "<html><head><title>" + action + "</title></head><body>" + appContext + " : " + action +" : "+ ret + "</body></html>";

		try
		{
			OutputStream out = resp.getOutputStream();
			byte[] outBytes = retStr.getBytes();
			out.write(outBytes);
		}
		catch (Exception e)
		{
			WMSLoggerFactory.getLogger(null).error("HttpProviderReloadSchedule: " + e.toString(), e);
		}

	}

}
