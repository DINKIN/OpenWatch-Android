package net.openwatch.reporter.recording.audio;

import android.media.AudioFormat;
import android.media.AudioRecord;
import android.media.MediaRecorder;
import android.util.Log;

/*
 * This class polls audio from the microphone and fills a
 * circular buffer. 
 * 
 * Data is accessed by calling emptyBuffer(), which returns
 * all available unread samples. The output length is guaranteed to be a multiple of 
 * recorderTask.samples_per_frame for convenient input to an audio encoder
 * 
 * Usage:
 * 
 * 1. AudioSoftwarePoller recorder = new AudioSoftwarePoller();
 * 1a (optional): recorder.setSamplesPerFrame(NUM_SAMPLES_PER_CODEC_FRAME)
 * 2. recorder.startPolling(); 
 * 3. short[] audio_data = recorder.emptyBuffer();
 * 4. recorder.stopPolling();
 */
public class AudioSoftwarePoller {

	public static final String TAG = "AudioSoftwarePoller";
	
	public static final int SAMPLE_RATE = 44100;
	public static final int CHANNEL_CONFIG = AudioFormat.CHANNEL_IN_MONO;
	public static final int AUDIO_FORMAT = AudioFormat.ENCODING_PCM_16BIT;
	
	public static final int FRAMES_PER_BUFFER = 43; // 1 sec @ 1024 samples/frame (aac)
	
	public static boolean is_recording = false;
	
	public RecorderTask recorderTask = new RecorderTask();
		
	// reused readAudioFrames() variables
	int read_index;
	int write_index;
	int distance; 				// difference between write / read indexes
	public int read_distance; 	// the greatest multiple of samples_per_frame < distance
	int tail_distance; 			// if the buffer_write_index < buffer_read_index, how many items shall
					   			// we copy from the buffer tail before resuming from the buffer head
	
	public AudioSoftwarePoller(){
	}
	
	/**
	 * Set the number of samples per frame (Default is 1024). Call this before startPolling(). 
	 * The output of emptyBuffer() will be equal to, or a multiple of, this value. 
	 * @param samples_per_frame The desired audio frame size in samples.
	 */
	public void setSamplesPerFrame(int samples_per_frame){
		if(!is_recording)
			recorderTask.samples_per_frame = samples_per_frame;
	}
	
	/**
	 * Begin polling audio and transferring it to the buffer. Call this before emptyBuffer().
	 */
	public void startPolling() {
		//recorderTask.execute();
		new Thread(recorderTask).start();
	}

	/**
	 * Stop polling audio.
	 */
	public void stopPolling() {
		is_recording = false; 		// will stop recording after next sample received
									// by recorderTask
	}

	/**
	 * Reads audio samples from the buffer.
	 * @return all audio data polled since the last call to this method. Output length is equal to, or a multiple of, samples_per_frame. 
	 * Set samples per frame with setSamplesPerFrame. If there is less than samples_per_frame samples ready, returns null.
	 */
	public short[] emptyBuffer(){
		short[] audio_samples = null;
		read_index = recorderTask.buffer_read_index;
		write_index = recorderTask.buffer_write_index;
		
		if(write_index == 0 || write_index < recorderTask.samples_per_frame){
			Log.i("AUDIO_READ_BUFFER", "Buffer empty or smaller than samples_per_frame");
			read_distance = 0;
			return audio_samples; // if samples aren't ready, there's nothing to do
		}
		// Compute distance between read & write indexes in circular buffer
		if(write_index < read_index)
			distance = recorderTask.buffer_size - Math.abs((write_index - read_index));
		else
			distance = write_index - read_index;
		
		read_distance = (distance / recorderTask.samples_per_frame) * recorderTask.samples_per_frame;
		
		audio_samples = new short[read_distance];
		
		if(write_index < read_index){
			tail_distance = recorderTask.buffer_size - read_index;
			// copy from buffer_read_index to end of buffer
			System.arraycopy(recorderTask.data_buffer, read_index, audio_samples, 0, tail_distance);
			// copy from start of buffer to buffer_write_index
			System.arraycopy(recorderTask.data_buffer, 0, audio_samples, tail_distance-1, read_distance - tail_distance);
		}else
			System.arraycopy(recorderTask.data_buffer, read_index, audio_samples, 0, read_distance);
		
		//Log.i("AUDIO_READ_BUFFER",String.valueOf(read_index) + " - " + String.valueOf(write_index-1) + " dist: "+ String.valueOf(read_distance));
		
		recorderTask.buffer_read_index = write_index;
		recorderTask.total_frames_read += (distance / recorderTask.samples_per_frame);
		return audio_samples;
	}
	
	
	public class RecorderTask implements Runnable {
		public int buffer_size;
		
		public int samples_per_frame = 1024; 	// codec-specific
		public int buffer_write_index = 0; 		// last buffer index written to
		public int buffer_read_index = 0; 		// first buffer index to read from
		
		public short[] data_buffer;
		
		public int total_frames_written = 0;
		public int total_frames_read = 0;
						
		public void run() { 
			int min_buffer_size = AudioRecord.getMinBufferSize(SAMPLE_RATE, CHANNEL_CONFIG, AUDIO_FORMAT);

			buffer_size = samples_per_frame * FRAMES_PER_BUFFER;
			
			// Ensure buffer is adequately sized for the AudioRecord 
			// object to initialize
			if(buffer_size < min_buffer_size)
				buffer_size = ((min_buffer_size / samples_per_frame) + 1) * samples_per_frame * 2;
			
			data_buffer = new short[buffer_size]; // filled directly by hardware
			
			AudioRecord audio_recorder;			
			audio_recorder = new AudioRecord(
					MediaRecorder.AudioSource.MIC, 		 // source
					SAMPLE_RATE, 						 // sample rate, hz
					CHANNEL_CONFIG,		 				 // channels 
					AUDIO_FORMAT, 	 					 // audio format
					buffer_size);	 					 // buffer size (bytes)
			
			is_recording = true;
			audio_recorder.startRecording();
			Log.i("AUDIO_REC","SW recording begin");
			while (is_recording)
	        {
	            audio_recorder.read(data_buffer, buffer_write_index, samples_per_frame);
	            //Log.i("AUDIO_FILL_BUFFER",String.valueOf(buffer_write_index) + " - " + String.valueOf(buffer_write_index + samples_per_frame-1));
	            buffer_write_index = (buffer_write_index + samples_per_frame) % buffer_size;
	            total_frames_written ++;

	        }
			if(audio_recorder != null){
				audio_recorder.setRecordPositionUpdateListener(null);
				audio_recorder.release();
				audio_recorder = null;
				Log.i("AUDIO_REC", "stopped");
			}			
		} 
	}

} // AudioSoftwareRecorder
