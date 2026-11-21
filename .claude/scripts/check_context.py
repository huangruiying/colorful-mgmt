#!/usr/bin/env python3
"""检查本项目协作文件的引用、可达性、配置与重复正文。

只读项目文件，不扫描业务数据、会话或凭据，不执行配置中的命令。
命令行失败返回 1；--hook 消费客户端事件并最多请求一次修正。
"""
import argparse
from collections import defaultdict
import json
from pathlib import Path
import re
import sys
from urllib.parse import unquote, urlsplit


LINK = re.compile(r'(?<!!)\[[^\]\n]+\]\((<[^>]+>|[^)\s]+)(?:\s+"[^"]*")?\)')


def prose_lines(text):
    """返回围栏代码块以外的行号与正文，防止把示例当成真实引用。"""
    fence = None
    for number, line in enumerate(text.splitlines(), 1):
        marker = re.match(r'^\s*(`{3,}|~{3,})', line)
        if marker:
            token = marker.group(1)
            if fence is None:
                fence = token
            elif token[0] == fence[0] and len(token) >= len(fence):
                fence = None
            continue
        if fence is None:
            yield number, line


def heading_ids(text):
    """生成常用 Markdown 标题锚点，兼容重复标题后缀与中文标题。"""
    counts, result = defaultdict(int), set()
    for _, line in prose_lines(text):
        heading = re.match(r'^#{1,6}\s+(.+?)\s*#*$', line)
        if heading:
            slug = re.sub(r'[^\w\- ]', '', heading.group(1).lower()).replace(' ', '-')
            count = counts[slug]
            result.add(slug if count == 0 else f'{slug}-{count}')
            counts[slug] += 1
    return result


def load_json(path):
    """读取 JSON 并拒绝重复键，避免后一个同名配置静默覆盖前一个。"""
    def unique_pairs(pairs):
        result = {}
        for key, value in pairs:
            if key in result:
                raise ValueError('JSON 存在重复键')
            result[key] = value
        return result
    return json.loads(path.read_text(encoding='utf-8'), object_pairs_hook=unique_pairs)


def check_links(root, texts):
    """核对本地链接和章节，返回问题及有效 Markdown 引用图。

    远程链接不联网验证；本地目标只允许仓库内真实存在的路径。
    """
    errors, edges = [], defaultdict(set)
    for path, text in texts.items():
        for number, line in prose_lines(text):
            for match in LINK.finditer(line):
                target = match.group(1).strip('<>')
                parts = urlsplit(target)
                if parts.scheme or parts.netloc:
                    continue
                relative = unquote(parts.path)
                linked = (path.parent / relative).resolve() if relative else path
                location = f'{path.relative_to(root)}:{number}'
                if not linked.is_relative_to(root):
                    errors.append(f'{location} 本地链接越出仓库')
                elif not linked.exists():
                    errors.append(f'{location} 本地链接目标不存在：{relative}')
                else:
                    edges[path].add(linked)
                    if parts.fragment and linked.suffix == '.md':
                        if unquote(parts.fragment) not in heading_ids(linked.read_text(encoding='utf-8')):
                            errors.append(f'{location} 本地章节不存在：{parts.fragment}')
    return errors, edges


def check_rule_scopes(root, texts):
    """校验本仓库采用的 paths 列表格式，避免损坏的 YAML 退化为全局加载。

    仅支持维护文档约定的带引号字符串列表，不声称是通用 YAML 解析器。
    """
    errors = []
    for path, text in texts.items():
        if not path.is_relative_to(root / '.claude/rules') or path.name == 'project-conventions.md':
            continue
        header = re.match(r'\A---\npaths:\n((?:  - "[^"\n]+"\n)+)---\n', text)
        if header is None:
            errors.append(f'{path.relative_to(root)} 缺少有效的 paths 列表')
            continue
        for line in header.group(1).splitlines():
            pattern = json.loads(line[4:])
            if not pattern or pattern.startswith('/') or '..' in pattern.split('/'):
                errors.append(f'{path.relative_to(root)} paths 必须限定仓库内路径')
    return errors


def check_hooks(configs):
    """校验两个客户端的已配置 hook 结构，不执行任何命令。"""
    errors = []
    for name, config in configs.items():
        if not isinstance(config, dict):
            errors.append(f'{name} 顶层必须是对象')
            continue
        hooks = config.get('hooks')
        if not isinstance(hooks, dict) or set(hooks) != {'SessionStart', 'Stop'}:
            errors.append(f'{name} 必须配置 SessionStart 和 Stop')
            continue
        for event, groups in hooks.items():
            if not isinstance(groups, list) or not groups:
                errors.append(f'{name} {event} 缺少事件处理组')
                continue
            for group in groups:
                handlers = group.get('hooks') if isinstance(group, dict) else None
                if not isinstance(handlers, list) or not handlers:
                    errors.append(f'{name} {event} 缺少处理器')
                    continue
                for handler in handlers:
                    if not isinstance(handler, dict):
                        errors.append(f'{name} {event} 处理器必须是对象')
                        continue
                    command = handler.get('command', '')
                    if (handler.get('type') != 'command' or not isinstance(command, str)
                            or '.claude/scripts/check_context.py' not in command
                            or '--hook' not in command or handler.get('timeout') != 10):
                        errors.append(f'{name} {event} 未使用约定的限时共用检查器')
    return errors


def inspect(root):
    """检查协作上下文，返回错误与提醒；不把语义审计伪装成机械校验。

    参数 root 为当前仓库根路径，错误阻断命令行，重复候选只提醒。
    """
    root = root.resolve()
    files = sorted((root / '.claude').rglob('*.md')) + [root / 'AGENTS.md']
    required = [root / '.claude/CLAUDE.md', root / 'AGENTS.md',
                root / '.claude/settings.json', root / '.codex/hooks.json']
    errors = [f'{p.relative_to(root)} 必需入口或配置缺失' for p in required if not p.is_file()]
    texts = {}
    for path in files:
        if path.is_file():
            try:
                texts[path.resolve()] = path.read_text(encoding='utf-8')
            except UnicodeError:
                errors.append(f'{path.relative_to(root)} 不是有效 UTF-8')
    links, edges = check_links(root, texts)
    errors.extend(links)
    errors.extend(check_rule_scopes(root, texts))
    # 从实际入口遍历；新加规则没有加载路径时直接报错。
    reachable, pending = set(), [root / 'AGENTS.md', root / '.claude/CLAUDE.md']
    while pending:
        current = pending.pop()
        if current not in reachable:
            reachable.add(current)
            pending.extend(edges[current])
    errors.extend(f'{p.relative_to(root)} 无入口引用，无法按路由加载'
                  for p in texts if p not in reachable)
    paragraphs = defaultdict(list)
    for path, text in texts.items():
        for number, line in prose_lines(text):
            normalized = re.sub(r'\s+', '', line).lstrip('-*0123456789. ')
            if len(normalized) >= 60 and not line.lstrip().startswith(('#', '|', '>')):
                paragraphs[normalized].append(f'{path.relative_to(root)}:{number}')
    warnings = ['重复正文候选：' + '、'.join(locations)
                for locations in paragraphs.values() if len(locations) > 1]
    configs = {}
    for folder in ('.claude', '.codex'):
        for path in sorted((root / folder).glob('*.json')):
            try:
                config = load_json(path)
                if path.name in ('settings.json', 'hooks.json'):
                    configs[str(path.relative_to(root))] = config
            except (ValueError, UnicodeError):
                # 不输出原始 JSON 或异常片段，防止本地配置里的敏感值泄漏。
                errors.append(f'{path.relative_to(root)} JSON 无效或包含重复键')
    errors.extend(check_hooks(configs))
    return errors, warnings


def hook_result(event, errors, warnings):
    """返回客户端支持的事件响应，Stop 已续跑时仅报告、不再次阻止结束。"""
    name = event.get('hook_event_name')
    summary = '；'.join(errors[:8])
    if name == 'SessionStart':
        context = ('当前仓库规则入口为 .claude/CLAUDE.md，请按路由完整读取适用正文。'
                   '本检查仅验证协作文件结构，不证明规则已读、代码正确或测试通过。')
        if summary:
            context += ' 协作文件待修正：' + summary
        return {'hookSpecificOutput': {'hookEventName': 'SessionStart', 'additionalContext': context}}
    if name == 'Stop' and errors:
        if event.get('stop_hook_active'):
            return {'systemMessage': '协作文件检查仍失败，停止重复续跑；交付须如实报告：' + summary}
        return {'decision': 'block', 'reason': '协作文件检查失败，请修正或如实报告阻断：' + summary}
    if name == 'Stop' and warnings:
        return {'systemMessage': f'协作文件有 {len(warnings)} 处重复候选，手动检查正文归属。'}
    return {}


def main():
    """执行只读命令行检查或 hook；仓库始终由脚本位置确定而不信任事件路径。"""
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--hook', action='store_true')
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    event = None
    if args.hook:
        try:
            event = json.load(sys.stdin)
            if not isinstance(event, dict):
                raise ValueError('event must be object')
        except (ValueError, UnicodeError):
            print(json.dumps({'systemMessage': '协作检查收到无效事件，未执行检查。'}, ensure_ascii=False))
            return 0
    try:
        errors, warnings = inspect(root)
    except (OSError, ValueError):
        if args.hook:
            print(json.dumps({'systemMessage': '协作文件读取失败，请手动运行检查器。'}, ensure_ascii=False))
            return 0
        print('协作文件读取失败，请核对文件权限与链接格式。', file=sys.stderr)
        return 1
    if args.hook:
        print(json.dumps(hook_result(event, errors, warnings), ensure_ascii=False))
        return 0
    for issue in errors:
        print('错误：' + issue)
    for issue in warnings:
        print('提醒：' + issue)
    print(f'协作文件检查：{len(errors)} 个错误，{len(warnings)} 个重复候选。')
    return int(bool(errors))


if __name__ == '__main__':
    sys.exit(main())
