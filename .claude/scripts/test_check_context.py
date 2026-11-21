"""用隔离目录验证协作检查器会识别真实错误，同时不因示例或提醒阻断交付。"""
from contextlib import contextmanager
import json
from pathlib import Path
import shutil
import subprocess
import sys
import tempfile
import unittest

import check_context as checker


@contextmanager
def repository():
    """构建路径含空格的最小仓库，提供根目录；结束后清理测试夹具。"""
    with tempfile.TemporaryDirectory(prefix='context test ') as temp:
        root = Path(temp).resolve()
        (root / '.claude/scripts').mkdir(parents=True)
        (root / '.codex').mkdir()
        (root / 'AGENTS.md').write_text('[入口](.claude/CLAUDE.md)\n')
        (root / '.claude/CLAUDE.md').write_text('# 入口\n')
        for path in ('.claude/settings.json', '.codex/hooks.json'):
            config = {'hooks': {event: [{'hooks': [{'type': 'command',
                       'command': 'python3 .claude/scripts/check_context.py --hook', 'timeout': 10}]}]
                       for event in ('SessionStart', 'Stop')}}
            (root / path).write_text(json.dumps(config))
        yield root


class ContextChecks(unittest.TestCase):
    """检查链接图、配置和结束事件的故障边界。"""

    def test_valid_tree_and_code_examples(self):
        with repository() as root:
            (root / '.claude/CLAUDE.md').write_text('''# 入口
````markdown
[不存在的示例](missing.md)
```java
x();
```
````
''')
            self.assertEqual(([], []), checker.inspect(root))

    def test_broken_and_escaping_links(self):
        with repository() as root:
            (root / '.claude/CLAUDE.md').write_text('[已删文件](rules/deleted.md)\n[越界](../../secret.md)')
            errors, _ = checker.inspect(root)
            self.assertEqual(2, len(errors))
            self.assertTrue(any('目标不存在' in e for e in errors))
            self.assertTrue(any('越出仓库' in e for e in errors))

    def test_heading_links_and_unreachable_rule(self):
        with repository() as root:
            (root / '.claude/orphan.md').write_text('# 未接入规则\n')
            entry = root / '.claude/CLAUDE.md'
            entry.write_text('# 中文 标题\n[有效](#中文-标题)\n[错误](#已删除章节)\n')
            errors, _ = checker.inspect(root)
            self.assertEqual(2, len(errors))
            self.assertTrue(any('章节不存在' in e for e in errors))
            self.assertTrue(any('无入口引用' in e for e in errors))

    def test_percent_encoded_paths_and_duplicate_headings(self):
        with repository() as root:
            (root / '.claude/有 空格.md').write_text('# 重复\n# 重复\n')
            (root / '.claude/CLAUDE.md').write_text('[有效](有%20空格.md#重复-1)\n')
            self.assertEqual(([], []), checker.inspect(root))

    def test_duplicate_prose_warns_without_blocking(self):
        with repository() as root:
            phrase = '此处是一条需要由人工判断是否应合并的长规则正文。' * 4
            (root / '.claude/CLAUDE.md').write_text(f'[另一份](other.md)\n- {phrase}\n')
            (root / '.claude/other.md').write_text(f'- {phrase}\n')
            errors, warnings = checker.inspect(root)
            self.assertEqual([], errors)
            self.assertEqual(1, len(warnings))
            self.assertNotIn('decision', checker.hook_result({'hook_event_name': 'Stop'}, errors, warnings))

    def test_invalid_json_duplicate_keys_and_wrong_hook(self):
        for payload in ('{', '{"hooks":{},"hooks":{}}', '{"hooks": []}', '[]'):
            with self.subTest(payload=payload), repository() as root:
                (root / '.codex/hooks.json').write_text(payload)
                errors, _ = checker.inspect(root)
                self.assertTrue(errors)
                self.assertFalse(any(payload in error for error in errors))

    def test_missing_required_file(self):
        with repository() as root:
            (root / '.codex/hooks.json').unlink()
            errors, _ = checker.inspect(root)
            self.assertTrue(any('必需入口或配置缺失' in error for error in errors))

    def test_malformed_scope_cannot_silently_become_global(self):
        with repository() as root:
            (root / '.claude/rules').mkdir()
            rule = root / '.claude/rules/example.md'
            (root / '.claude/CLAUDE.md').write_text('[专题](rules/example.md)\n')
            rule.write_text('---\npaths: [broken\n---\n# 专题\n')
            errors, _ = checker.inspect(root)
            self.assertTrue(any('paths' in error for error in errors))
            rule.write_text('---\npaths:\n  - "src/**/*.java"\n---\n# 专题\n')
            self.assertEqual(([], []), checker.inspect(root))

    def test_project_memory_needs_no_rule_scope(self):
        """
        功能:
            验证项目记忆不受通用规则作用域检查影响。
        作用:
            防止把迁入记忆的项目约定误判成缺少 paths 的规则。
        参数:
            self:    当前测试用例
        返回:
            无；不符合约定时由断言报告失败。
        """
        # 1. 构造无 paths 的项目记忆并接到主题读取入口
        with repository() as root:
            (root / '.claude/memory').mkdir()
            (root / '.claude/memory/project-conventions.md').write_text('# 项目约定\n')
            (root / '.claude/CLAUDE.md').write_text('[记忆](memory/project-conventions.md)\n')
            # 2. 记忆文件不应要求 rules 专用元数据
            self.assertEqual(([], []), checker.inspect(root))

    def test_rules_have_no_project_conventions_exemption(self):
        """
        功能:
            验证 project-conventions 名称不再豁免规则作用域。
        作用:
            防止旧分类例外使误放的项目文件无条件全局加载。
        参数:
            self:    当前测试用例
        返回:
            无；未识别无作用域规则时由断言报告失败。
        """
        # 1. 模拟将旧项目约定文件重新放回 rules
        with repository() as root:
            (root / '.claude/rules').mkdir()
            (root / '.claude/rules/project-conventions.md').write_text('# 旧位置\n')
            (root / '.claude/CLAUDE.md').write_text('[规则](rules/project-conventions.md)\n')
            # 2. 文件名不能代替缺失的作用域约束
            errors, _ = checker.inspect(root)
            self.assertTrue(any('paths' in error for error in errors))

    def test_stop_continues_only_once(self):
        errors = ['入口失效']
        first = checker.hook_result({'hook_event_name': 'Stop'}, errors, [])
        second = checker.hook_result({'hook_event_name': 'Stop', 'stop_hook_active': True}, errors, [])
        self.assertEqual('block', first['decision'])
        self.assertNotIn('decision', second)
        self.assertIn('仍失败', second['systemMessage'])

    def test_session_start_reports_limit_and_errors(self):
        result = checker.hook_result({'hook_event_name': 'SessionStart'}, ['坏链接'], [])
        context = result['hookSpecificOutput']['additionalContext']
        self.assertIn('不证明', context)
        self.assertIn('坏链接', context)

    def test_cli_and_event_protocol_on_space_path(self):
        with repository() as root:
            script = root / '.claude/scripts/check_context.py'
            shutil.copyfile(Path(checker.__file__), script)
            valid = subprocess.run([sys.executable, str(script)], capture_output=True, text=True, check=False)
            self.assertEqual(0, valid.returncode, valid.stdout)
            (root / '.claude/CLAUDE.md').write_text('[坏链接](missing.md)\n')
            invalid = subprocess.run([sys.executable, str(script)], capture_output=True, text=True, check=False)
            self.assertEqual(1, invalid.returncode)
            for event in ({'hook_event_name': 'Stop'},
                          {'hook_event_name': 'Stop', 'stop_hook_active': True},
                          {'hook_event_name': 'SessionStart'}):
                response = subprocess.run([sys.executable, str(script), '--hook'], input=json.dumps(event),
                                          capture_output=True, text=True, check=False)
                self.assertEqual(0, response.returncode, response.stderr)
                self.assertIsInstance(json.loads(response.stdout), dict)
            malformed = subprocess.run([sys.executable, str(script), '--hook'], input='not json',
                                       capture_output=True, text=True, check=False)
            self.assertIn('未执行', json.loads(malformed.stdout)['systemMessage'])


if __name__ == '__main__':
    unittest.main()
