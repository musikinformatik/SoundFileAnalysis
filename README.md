# SoundFileAnalysis
A simple system for custom non realtime soundfile analysis in SuperCollider

a simple example:

````
(
var analysis, path;
analysis = SoundFileAnalysis.new;
// add some custom sound analysis method
analysis.add(\my_trig_amps, \trig, { |sig|
	var amp = Amplitude.kr(sig);
	var trig = amp > 0.6; // amp exceeds threshold
	var avg = TrigAvg.kr(amp, trig);
	[trig, avg] // records always the average before the peak
});

path = Platform.resourceDir +/+ "sounds/a11wlk01.wav";
x = analysis.analyzeFile(path, callback: { "analysis completed".postln; });
)

x[\my_trig_amps].plot; // plot the average amplitudes
````
