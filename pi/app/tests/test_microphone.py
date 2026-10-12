import json
import subprocess
import sys
import unittest
from pathlib import Path
from unittest.mock import patch

sys.path.insert(0, str(Path(__file__).parents[1]))
from server import usb_microphone_status


class MicrophoneSourceTests(unittest.TestCase):
    def status(self, responses):
        with patch('os.getuid', return_value=1000, create=True), patch('subprocess.run', side_effect=responses):
            return usb_microphone_status()

    def test_usb_default_must_still_exist(self):
        name = 'alsa_input.usb-Insta360-02.mono-fallback'
        result = self.status([subprocess.CompletedProcess([], 0, name),
                              subprocess.CompletedProcess([], 0, json.dumps([{'name':name, 'description':'Insta360'}]))])
        self.assertTrue(result['available'])
        self.assertEqual(result['source'], name)
        absent = self.status([subprocess.CompletedProcess([], 0, name), subprocess.CompletedProcess([], 0, '[]')])
        self.assertFalse(absent['available'])

    def test_playback_monitor_never_becomes_input(self):
        result = self.status([subprocess.CompletedProcess([], 0, 'alsa_output.hdmi.monitor')])
        self.assertFalse(result['available'])

    def test_audio_server_timeout_is_unavailable(self):
        result = self.status([subprocess.TimeoutExpired('pactl', 2)])
        self.assertFalse(result['available'])


if __name__ == '__main__':
    unittest.main()
