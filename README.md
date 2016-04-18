# StreamPublisher
The **StreamPublisher** module for [Wowza Streaming Engine™ media server software](https://www.wowza.com/products/streaming-engine) lets you use a server listener and application module to create a schedule of streams and playlists. Using television as an analogy, a stream is a channel and a playlist is a program with one or more video segments. A schedule can have as many streams (channels) as you want, with as many playlists (programs) as you want, and each playlist can be scheduled to play on a stream at a certain time. If a playlist is scheduled to start on a stream while another playlist is running, the existing playlist is replaced with the new one. If you set a schedule to begin in the past, the playlist plays immediately.

## Prerequisites
Wowza Streaming Engine 4.0.0 or later is required.

## Usage
You can create a schedule with server listener and application module methods, either together or separately, depending on your needs.

You can use the **ServerListenerStreamPublisher** server listener to load a set of scheduled streams on a single application when the media server starts. This procedure keeps the streams running until the server is shut down. If you use this process, the schedule can't be reloaded by using just the server listener.

You can use the **ModuleStreamPublisher** application module on any application to load a set of scheduled streams on that application when the application starts and unload the streams when the application is shut down. The schedule can be reloaded by modifying the SMIL file for the application and then reloading it. The module can provide the reload functionality to the schedule that's configured in the server listener. It can also be used on its own, in separate applications, to provide separate schedules for each application. Each application that runs a schedule must have a **live** stream type.

To use the included **ModuleLoopUntilLive** application module to loop pre-roll video around a live stream, at least one server-side stream must be configured on the Streaming Engine live application.

## More resources
[Wowza Streaming Engine Server-Side API Reference](https://www.wowza.com/resources/WowzaStreamingEngine_ServerSideAPI.pdf)

[How to extend Wowza Streaming Engine using the Wowza IDE](https://www.wowza.com/forums/content.php?759-How-to-extend-Wowza-Streaming-Engine-using-the-Wowza-IDE)

Wowza Media Systems™ provides developers with a platform to create streaming applications and solutions. See [Wowza Developer Tools](https://www.wowza.com/resources/developers) to learn more about our APIs and SDK.

To use the compiled version of this module, see [How to schedule streaming with Wowza Streaming Engine (StreamPublisher)](https://www.wowza.com/forums/content.php?145-How-to-schedule-streaming-with-Wowza-Streaming-Engine-%28StreamPublisher%29).

For instructions on using the **ModuleLoopUntilLive** application module, see [How to loop a pre-roll until a live stream starts (ModuleLoopUntilLive)](https://www.wowza.com/forums/content.php?468-How-to-loop-a-pre-roll-until-a-live-stream-starts-%28ModuleLoopUntilLive%29).

## Contact
[Wowza Media Systems, LLC](https://www.wowza.com/contact)

## License
This code is distributed under the [Wowza Public License](https://github.com/WowzaMediaSystems/wse-plugin-streampublisher/blob/master/LICENSE.txt).
