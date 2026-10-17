"""Refusal checks for the opt-in native proof's pre-execution boundary."""
import copy
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

from prove_macos_process_routing import assert_loopback_fixture
from verify_macos_per_app import check, verified_archive


class ProofBoundaryTests(unittest.TestCase):
    def fixture(self):
        return {'log': {'disabled': True},
                'inbounds': [{'type': 'direct', 'tag': 'perroute-tun-spike', 'listen': '127.0.0.1',
                              'listen_port': 30001, 'network': 'tcp', 'override_address': '127.0.0.1',
                              'override_port': 30002}],
                'outbounds': [{'type': 'direct', 'tag': 'unmatched-direct'}] + [
                    {'type': 'socks', 'tag': f'xray-loopback-{port}', 'server': '127.0.0.1',
                     'server_port': port, 'version': '5'} for port in (30003, 30004)],
                'route': {'auto_detect_interface': True, 'rules': [], 'final': 'unmatched-direct'}}

    def validate(self, config):
        assert_loopback_fixture(config, 30001, 30002, [30003, 30004])

    def test_fixed_loopback_fixture(self):
        self.validate(self.fixture())

    def test_refuse_capture_and_other_destinations(self):
        mutations = [
            lambda c: c['inbounds'][0].update(type='tun', auto_route=True),
            lambda c: c['inbounds'][0].update(listen='0.0.0.0'),
            lambda c: c['inbounds'][0].update(override_address='192.0.2.1'),
            lambda c: c['inbounds'][0].update(override_port=443),
            lambda c: c['inbounds'][0].update(network='udp'),
            lambda c: c['outbounds'][1].update(server='192.0.2.1'),
            lambda c: c['outbounds'].append({'type': 'http', 'server': '192.0.2.1'}),
            lambda c: c.update(dns={'servers': []}),
            lambda c: c['route'].update(rule_set=[{'url': 'https://example.invalid'}]),
            lambda c: c.update(log={'level': 'debug'}),
        ]
        for mutate in mutations:
            config = copy.deepcopy(self.fixture())
            mutate(config)
            with self.subTest(config=config), self.assertRaises(ValueError):
                self.validate(config)

    def test_tampered_archive(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'archive.tar.gz'
            path.write_bytes(b'tampered')
            with self.assertRaisesRegex(ValueError, 'digest'):
                verified_archive(path, 'arm64')

    def test_checker_refuses_activation_before_staging(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / 'candidate.json'
            path.write_text('{"inbounds":[{"type":"tun","auto_route":true}]}')
            with patch('verify_macos_per_app.verified_engine') as stage:
                with self.assertRaises(ValueError):
                    check(path)
                stage.assert_not_called()


if __name__ == '__main__':
    unittest.main()
