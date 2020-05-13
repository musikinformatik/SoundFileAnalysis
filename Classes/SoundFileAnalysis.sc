SoundFileAnalysis {

	classvar <basicAnalysisMethods, <>verbose = false;
	var <analysisMethods;


	*initClass {
		this.initAnalysisMethods;
	}

	*new {
		^super.new.init
	}

	init {
		analysisMethods = ();
	}

	add { |name, type, ugenFunc|
		analysisMethods.put(name, { |sig, numChannels, soundfile, server, maxDataPoints|
			var buildFunc = basicAnalysisMethods.at(type);
			if(buildFunc.isNil) { "analysis type '%' for ugenFunc '%' not found".format(type, name).throw };
			buildFunc.value(sig, ugenFunc, numChannels, soundfile, server, maxDataPoints)
		})
	}

	// todo: allow to partly separate processes
	analyzeAll { |paths, start = 0, duration, which, callback, maxDataPoints|
		var result = List.new;
		if(paths.isString or: paths.isCollection.not) { paths = [paths] };
		which = (which ?? { analysisMethods.keys.as(Array).sort }).asArray;
		fork {
			var cond = Condition.new;
			paths.do { |path|
				var subresult = (), dataEvent;
				which.do { |methods|
					dataEvent = this.analyzeFile(path, start, duration, methods, { cond.unhang }, maxDataPoints);
					cond.hang;
					subresult.putAll(dataEvent);
				};
				subresult.use {
					~dataDimensions = which;
					~dataTable = ~dataTable.value;
				};
				result.add(subresult);
			};
			callback.value(result);
		};
		^result
	}

	analyzeFile { |path, start = (0), duration, which, callback, maxDataPoints = (1000),trimDuration=\roundDown, serverOptions|
		var result = ();
		if(File.exists(path).not) { "\nFile not found: %\n".format(path).warn; ^this };
		which = (which ?? { analysisMethods.keys.as(Array).sort }).asArray;
		fork {
			var resultpaths, oscpath, score;
			var analysisDuration, soundFile, options, cond;
			var server = Server(\dummy);

			// get duration and numChannels from soundFile
			soundFile = SoundFile.openRead(path);

			options= (serverOptions ?? {ServerOptions()})
				.verbosity_(-1)
				.memSize_(8192 * 256) // REALLY NEEDED?
				.sampleRate_(soundFile.sampleRate);
			);

			if(trimDuration.isNil){
				analysisDuration = duration;
			}{
				analysisDuration = min(duration ?? { soundFile.duration - start }, soundFile.duration);
			};
			if(trimDuration == \roundUp){
				analysisDuration=analysisDuration.roundUp(options.blockSize / soundFile.sampleRate);
			};

			soundFile.close;

			// first we build a score
			score = Score.new;
			resultpaths = this.prAddAnalysisToScore(score, which, server, analysisDuration, soundFile, maxDataPoints, start);

			cond = Condition.new;

			// then we record it
			// osc file path, output path, input path - input is soundfile to analyze
			// actually this isn't really needed, but I leave it in here.
			oscpath = PathName.tmp +/+ UniqueID.next ++ ".osc";
			score.recordNRT(oscpath, "/dev/null",
				//path,
				sampleRate: soundFile.sampleRate,
				options: options,
				action: { cond.unhang }  // this re-awakens the process after NRT is finished
			);

			result.put(\kill, { systemCmd("kill" + server.pid) });
			cond.hang;  // wait for completion

			this.prReadAnalysisFiles(result, resultpaths, which, maxDataPoints);

			File.delete(oscpath);
			Server.all.remove(server);

			result.use {
				try { ~fileName = path.basename }; // seems not to work properly under ubuntu
				~path = path;
				~analysisStart = start;
				~analysisDuration = analysisDuration;
				~fileNumChannels = soundFile.numChannels;
				~dataDimensions = which;
				~dataTable = { ~dataDimensions.collect { |name| result.at(name) } };
			};

			callback.value(result);
		};
		^result
	}


	prAddAnalysisToScore { |score, which, server, analysisDuration, soundFile, maxDataPoints = (1000), start|

		// build synthdefs and buffers ...

		var numChannels = 1; // for now
		var analysisChannel = 32;
		var synthDefFunctions, synthDefs, resultbufs, resultpaths, fileSynthDef, soundFileBuffer;

		// input streaming buffer
		soundFileBuffer = Buffer.new(server, 32768, soundFile.numChannels);
		fileSynthDef = SynthDef("temp__" ++ UniqueID.next, {
			var sig = DiskIn.ar(soundFile.numChannels, soundFileBuffer);
			//if(numChannels != soundFile.numChannels) { // TODO.
			if(soundFile.numChannels != 1) {
				if(verbose) {
					warn("no separate channels are currently supported: mixing % channels into one.".format(soundFile.numChannels))
				};
				sig = Mix(sig);
			};
			Out.ar(analysisChannel, sig)
		});

		synthDefs = List[fileSynthDef];

		// analysis defs
		synthDefFunctions = which.asArray.collect { |name|
			var func = analysisMethods[name];
			if(func.isNil) { Error("no analysis method found with this name:" + name).throw };
			func
		};
		synthDefFunctions.do { |synthDefFunc, i|
			var name = synthDefFunc.identityHash.abs.asSymbol;
			synthDefs.add(
				SynthDef(name, {
					var sig = In.ar(analysisChannel, 1);
					// this function returns a buffer
					var buffer = synthDefFunc.value(sig, numChannels, soundFile, server, maxDataPoints);
					resultbufs = resultbufs.add(buffer);
			}))
		};

		// ... then add them to the score
		score.add([0, soundFileBuffer.allocMsg]);
		score.add([0, soundFileBuffer.cueSoundFileMsg(soundFile.path, startFrame: start * soundFile.sampleRate)]);
		resultbufs.do { |buf| score.add([0, buf.allocMsg]) };
		synthDefs.do { |def| score.add([0, [\d_recv, def.asBytes]]) };
		synthDefs.do { |def| score.add([0, Synth.basicNew(def.name, server).newMsg(addAction: \addToTail)]) };

		// write them to files and return paths for later reading
		resultpaths = resultbufs.collect { |buf|
			var path = PathName.tmp +/+ UniqueID.next ++ ".aiff";
			score.add([analysisDuration, buf.writeMsg(path, headerFormat: "AIFF", sampleFormat: "float")]);
			path
		};
		^resultpaths
	}


	prReadAnalysisFiles { |result, paths, which, maxDataPoints = (1000)| // result is a dictionary, data is added

		paths.do { |resultpath, i|

			var dataFile = SoundFile.new;
			var numDataPoints, numOutputChannels, data;
			dataFile.openRead(resultpath);
			if(dataFile.isOpen) {
				// get the numDataPoints: one frame at the start
				dataFile.readData(numDataPoints = FloatArray.newClear(1));
				numDataPoints = numDataPoints[0];

				if(numDataPoints.isNil) {
					"no numDataPoints information, assuming numDataPoints = 1".warn;
					numDataPoints = 1;
				};
				if(numDataPoints >= maxDataPoints) {
					"analysis exceeded maxDataPoints (%)".format(maxDataPoints).warn;
				};
				// now the rest of the data
				dataFile.readData(data = FloatArray.newClear(numDataPoints));
				dataFile.close;
				//("sound file read successful" + resultpath).postln;
			} {
				("sound file read failed" + resultpath).postln;
			};
			numOutputChannels = dataFile.numChannels;
			if(numOutputChannels.notNil and: { numOutputChannels != 1 }) {
				data = data.clump(numOutputChannels)
			};
			result.put(which.at(i), data);
			File.delete(resultpath);
		}

	}

	*initAnalysisMethods {
		basicAnalysisMethods = (
			// ugenFunc receives signal and soundfile as arguments
			// ugenFunc should return [trig, ... value], any number of values.
			// on trigger, all values (UGen outputs) will be written.
			// if the value is nil and only trig is returned, the current time in frames will be used
			// no demand rate support
			trig:{ |sig, ugenFunc, numChannels, soundfile, server, maxDataPoints|
				var trig, value, i, numOutputChannels, resultbuf;
				#trig ... value = ugenFunc.value(sig, soundfile).asArray;
				if(value.isEmpty) { value = [Phasor.ar(0, 1, 0, inf)] }; // then use frame count
				numOutputChannels = value.size;
				// we make the buffer two points larger, one for numDataPoints, one for the overrun:
				resultbuf = Buffer.new(server, maxDataPoints + 2, 1);
				value.do { |val, j|
					if(val.rate == \audio) {
						if(trig.rate != \audio) { trig = T2A.ar(trig) };
						i = PulseCount.ar(trig);
						BufWr.ar(val, resultbuf, i - 1 * numOutputChannels + j + 1, loop: 0); // 'i' must be audio-rate for BufWr.ar
						if(j == 0) { BufWr.ar(i * numOutputChannels, resultbuf, DC.ar(0), loop: 0) };  // number of total points in index 0, just once
					} {
						if(trig.rate != \control) { trig = T2K.kr(trig) };
						i = PulseCount.kr(trig);
						BufWr.kr(val, resultbuf, i - 1 * numOutputChannels + j + 1, loop: 0); // 'i' must be audio-rate for BufWr.ar
						if(j == 0) { BufWr.kr(i * numOutputChannels, resultbuf, DC.kr(0), loop: 0) };  // number of total points in index 0, just once
					}
				};
				resultbuf
			},
			// ugenFunc receives signal and soundfile as arguments
			// ugenFunc should return a continuous value, or an array of values (UGen output), which will be averaged over
			// no demand rate support
			average: { |sig, ugenFunc, numChannels, soundfile, server, maxDataPoints|
				var value, avg, numOutputChannels, resultbuf;
				value = ugenFunc.value(sig, soundfile).asArray;
				numOutputChannels = value.size;
				// we make the buffer two points larger, one for numDataPoints, one for the overrun:
				resultbuf = Buffer.new(server, maxDataPoints + 2, 1);
				value.do { |val, j|
					if(val.rate == \audio) {
						avg = Integrator.ar(val) * (1 / soundfile.numFrames);
						BufWr.ar(avg, resultbuf, DC.ar(j + 1), loop: 0);
					} {
						if(value.rate != \control) { value = DC.kr(val) };
						avg = Integrator.kr(val) * (server.options.blockSize / soundfile.numFrames);
						BufWr.kr(avg, resultbuf, DC.kr(j + 1), loop: 0);
					};
				};
				BufWr.kr(numOutputChannels, resultbuf, DC.kr(0), loop: 0);  // number of points in index 0: numOutputChannels.
				resultbuf
			},
			// ugenFunc receives signal and soundfile as arguments
			// ugenFunc should return a continuous value, or an array of values (UGen output), which will be  recorded frame by frame
			// no demand rate support
			direct: { |sig, ugenFunc, numChannels, soundfile, server, maxDataPoints|
				var value, numDataPoints, i, numOutputChannels, resultbuf;
				value = ugenFunc.value(sig, soundfile).asArray;
				numOutputChannels = value.size;

				numDataPoints = min(maxDataPoints, numOutputChannels * soundfile.numFrames);
				if(value.at(0).rate != \audio) {
					numDataPoints = numDataPoints / server.options.blockSize;
				};
				// we make the buffer two points larger, one for numDataPoints, one for the overrun:
				resultbuf = Buffer.new(server, maxDataPoints + 2, 1);
				value.do { |val, j|
					if(val.rate == \audio) {
						i = Phasor.ar(0, numOutputChannels, j + 1, inf); // let this one overrun, cut off when reading file
						DetectSilence.ar(i <= (maxDataPoints + 2), doneAction:2);
						BufWr.ar(val, resultbuf, phase: i, loop: 0);
					} {
						i = Phasor.kr(0, numOutputChannels, j + 1, inf); // let this one overrun, cut off when reading file
						FreeSelf.kr(i > (maxDataPoints + 2));
						if(value.rate != \control) { value = DC.kr(val) };
						BufWr.kr(val, resultbuf, phase: i, loop: 0);
					};
				};
				BufWr.kr(numDataPoints, resultbuf, DC.kr(0), loop: 0);
				resultbuf
			}
			// todo: FFT / pvCalc version.
		);
	}


}


