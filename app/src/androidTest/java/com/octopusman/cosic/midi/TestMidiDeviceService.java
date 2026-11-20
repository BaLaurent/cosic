package com.octopusman.cosic.midi;

import android.media.midi.MidiDeviceService;
import android.media.midi.MidiReceiver;
import java.io.IOException;

/**
 * A virtual MIDI device with one input port and one output port that echoes: everything the app sends to the input port comes back, at once and
 * in the pieces it arrived in, on the output port (to whoever listens to it). The system runs this service in a process of its own, which has
 * only the classes of the test APK (no Kotlin standard library), so it is written in Java, and a test sees what it does through the MIDI
 * connection itself.
 */
public class TestMidiDeviceService extends MidiDeviceService {
    private final MidiReceiver echo = new MidiReceiver() {
        @Override
        public void onSend(byte[] msg, int offset, int count, long timestamp) throws IOException {
            for (MidiReceiver receiver : getOutputPortReceivers()) {
                receiver.send(msg, offset, count, timestamp);
            }
        }
    };

    @Override
    public MidiReceiver[] onGetInputPortReceivers() {
        return new MidiReceiver[] {echo};
    }
}
