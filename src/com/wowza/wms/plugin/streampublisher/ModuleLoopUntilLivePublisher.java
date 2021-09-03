package com.wowza.wms.plugin.streampublisher;

import com.wowza.wms.application.IApplicationInstance;
import com.wowza.wms.logging.*;
import com.wowza.wms.module.ModuleBase;
import com.wowza.wms.stream.publish.*;
import com.wowza.wms.util.StreamUtils;

import java.util.*;
import java.util.concurrent.*;

public class ModuleLoopUntilLivePublisher extends ModuleBase
{
    private static final Class<ModuleStreamPublisher> CLASS = ModuleStreamPublisher.class;
    public static final String MODULE_NAME = CLASS.getSimpleName();
    private String outStreamNames = "Stream1";
    private String vodFileNames = "sample.mp4";
    private List<String> outStreamNamesList = new CopyOnWriteArrayList<>();
    private List<String> vodFileNamesList;
    private IApplicationInstance appInstance;
    private WMSLogger logger;

    public void onAppStart(IApplicationInstance appInstance)
    {
        this.appInstance = appInstance;
        logger = WMSLoggerFactory.getLoggerObj(appInstance);
        logger.info(MODULE_NAME + ".onAppStart: ["+appInstance.getContextStr()+"]: Build #8", WMSLoggerIDs.CAT_application, WMSLoggerIDs.EVT_comment);
        outStreamNames = appInstance.getProperties().getPropertyStr("loopUntilLiveOutputStreams", outStreamNames);
        vodFileNames = appInstance.getProperties().getPropertyStr("loopUntilLiveVodFiles", vodFileNames);
        outStreamNamesList.addAll(Arrays.asList(outStreamNames.split(",")));
        vodFileNamesList = Arrays.asList(vodFileNames.split(","));
        int fileCount = vodFileNamesList.size();
        for (int i = 0; i < outStreamNamesList.size(); i++)
        {
            String outStreamName = outStreamNamesList.get(i).trim();
            String vodFileName = vodFileNamesList.get(Math.min(i, fileCount - 1)).trim();
            startStream(outStreamName, vodFileName);
        }
    }

    public void startStream(String outStreamName, String vodFileName)
    {
        Stream stream = (Stream) appInstance.getProperties().get(outStreamName);
        if (stream != null)
        {
            logger.warn(MODULE_NAME + ".startStream [" + appInstance.getContextStr() + "/" + outStreamName + "] stream already running");
            return;
        }
        if (StreamUtils.getStreamLength(appInstance, vodFileName) <= 0)
        {
            logger.warn(MODULE_NAME + ".startStream [" + appInstance.getContextStr() + "/" + outStreamName + "] vod file not found: " + vodFileName);
            return;
        }
        stream = Stream.createInstance(appInstance, outStreamName);
        stream.play(vodFileName, 0, -1, true);
        appInstance.getProperties().setProperty(outStreamName, stream);
        if(!outStreamNamesList.contains(outStreamName))
            outStreamNamesList.add(outStreamName);
    }

    public void onAppStop(IApplicationInstance appInstance)
    {
        for (String streamName : outStreamNamesList)
            stopStream(streamName);
    }

    private void stopStream(String streamName)
    {
        Stream stream = (Stream) appInstance.getProperties().remove(streamName);
        if(stream != null)
        {
            stream.closeAndWait();
            Publisher publisher = stream.getPublisher();
            if(publisher != null)
                publisher.close();
        }
        outStreamNamesList.remove(streamName);
    }
}
