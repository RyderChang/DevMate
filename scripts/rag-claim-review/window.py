"""Local tkinter window. Only offsets, IDs and fixed choices leave this module."""
import tkinter as tk
from tkinter import ttk

from review import BINDING, CORE, MATCH, SUPPORT, TYPES, ReviewError


class ReviewWindow:
    def __init__(self, session, sink, visible=True):
        self.root = None
        try:
            self.build(session, sink, visible)
        except BaseException:
            if self.root is not None:
                try: self.root.destroy()
                except tk.TclError: pass
            raise ReviewError('WINDOW_UNAVAILABLE') from None

    def build(self, session, sink, visible):
        self.session, self.sink = session, sink
        self.root = tk.Tk()
        if not visible: self.root.withdraw()
        self.root.title('Transient claim review — do not copy, capture or save bodies')
        self.root.geometry('1150x900')
        self.root.protocol('WM_DELETE_WINDOW', self.cancel)
        self.root.report_callback_exception = lambda *_: self.fail()
        self.closed = False
        self.ranges, self.checks, self.links = [], [], []
        self.marker = None
        self.message = tk.StringVar(value='逐项登记后，检查全文、核心遗漏和所有返回引用。')
        ttk.Label(self.root, text=f'Q{session.context.question} · {session.context.arm} · {session.context.mode} · '
                  f'scope {session.context.scope[:12]} · sample {session.sample[:12]}').pack(anchor='w')
        ttk.Label(self.root, text=session.context.prompt, wraplength=1050).pack(anchor='w')
        location = lambda c: next(s for s, (h, _) in session.context.sources.items() if h == c['sourceSha256'])
        ttk.Label(self.root, text='核心证据：' + '; '.join(f'{e} {location(t)}:{t["startLine"]}–{t["endLine"]}'
                  for e, t in session.context.targets.items()), wraplength=1050).pack(anchor='w')
        self.answer = self.view(self.root, session.answer, height=10)
        source_panel = ttk.Frame(self.root); source_panel.pack(fill='x')
        self.source_id = self.combo(source_panel, '冻结来源', tuple(session.context.sources))
        ttk.Button(source_panel, text='显示完整来源', command=self.show_source).pack(side='left')
        ttk.Label(source_panel, text='C 对应行区间').pack(side='left')
        ttk.Label(source_panel, text='; '.join(f'{cid} {location(session.context.chunks[int(cid[1])-1])}:'
                                             f'{session.context.chunks[int(cid[1])-1]["startLine"]}–'
                                             f'{session.context.chunks[int(cid[1])-1]["endLine"]}'
                                             for cid in session.context.returned)).pack(side='left')
        self.source = self.view(self.root, '', height=8)
        self.show_source()
        draft = ttk.Frame(self.root); draft.pack(fill='x')
        ttk.Button(draft, text='将答案选区加入当前断言', command=self.select_claim).pack(side='left')
        ttk.Button(draft, text='将答案选区设为引用标记', command=self.select_marker).pack(side='left')
        ttk.Button(draft, text='清空草稿', command=self.reset).pack(side='left')
        self.offsets = tk.StringVar(value='区间 []；引用标记无')
        ttk.Label(self.root, textvariable=self.offsets).pack(anchor='w')
        fields = ttk.Frame(self.root); fields.pack(fill='x')
        self.kind = self.combo(fields, '分类', ('CORE', 'EXTRA', 'ABSTENTION'))
        self.support = self.combo(fields, '事实支持', SUPPORT, 'UNKNOWN')
        self.support_type = self.combo(fields, '支持类型', TYPES, 'UNKNOWN')
        self.unlinked = self.combo(fields, '无引用关系时的绑定', ('GLOBAL_ONLY', 'ABSENT', 'AMBIGUOUS', 'UNKNOWN'), 'UNKNOWN')
        self.core_checks = {}
        for e in CORE[session.context.question]:
            v = tk.BooleanVar(value=False); self.core_checks[e] = v
            ttk.Checkbutton(fields, text=e, variable=v).pack(side='left')
        evidence = ttk.Frame(self.root); evidence.pack(fill='x')
        self.first = tk.StringVar(value='1'); self.last = tk.StringVar(value='1')
        ttk.Label(evidence, text='校核来源使用上方 S 编号；证据行').pack(side='left')
        for v in (self.first, self.last): ttk.Spinbox(evidence, from_=1, to=10000, textvariable=v, width=6).pack(side='left')
        ttk.Button(evidence, text='加入校核证据', command=self.add_check).pack(side='left')
        citations = ttk.Frame(self.root); citations.pack(fill='x')
        self.cid = self.combo(citations, '返回引用', session.context.returned or ('无',))
        self.correspondence = self.combo(citations, '对应', MATCH, 'UNKNOWN')
        self.link_type = self.combo(citations, '证据类型', TYPES, 'UNKNOWN')
        self.binding = self.combo(citations, '就近绑定', BINDING, 'UNKNOWN')
        ttk.Button(citations, text='加入引用关系', command=self.add_link).pack(side='left')
        self.draft_metadata = tk.StringVar(value='证据 []；引用 []')
        ttk.Label(self.root, textvariable=self.draft_metadata).pack(anchor='w')
        buttons = ttk.Frame(self.root); buttons.pack(fill='x')
        ttk.Button(buttons, text='登记当前断言', command=self.add_claim).pack(side='left')
        ttk.Button(buttons, text='撤回最后断言', command=self.undo).pack(side='left')
        self.claim_list = tk.Listbox(self.root, height=4, exportselection=False); self.claim_list.pack(fill='x')
        finish = ttk.Frame(self.root); finish.pack(fill='x')
        self.disposition = {e: self.combo(finish, e, ('UNKNOWN', 'PRESENT', 'OMITTED')) for e in CORE[session.context.question]}
        self.relevance = {}
        for i, c in enumerate(session.context.returned):
            if i % 3 == 0:
                relevance_row = ttk.Frame(self.root); relevance_row.pack(fill='x')
            self.relevance[c] = self.combo(relevance_row, c + ' 相关性', ('UNKNOWN', 'RELEVANT', 'IRRELEVANT'))
        self.abstention = self.combo(finish, '弃答', SUPPORT, 'UNKNOWN') if session.context.question == 10 else None
        self.inventory = tk.BooleanVar(value=False); self.second = tk.BooleanVar(value=False)
        for label, v in (('全文断言清单完整（含额外事实、重复与限定词）', self.inventory),
                         ('第二遍核对全文、核心遗漏及所有返回引用已完成', self.second)):
            ttk.Checkbutton(self.root, text=label, variable=v).pack(anchor='w')
        controls = ttk.Frame(self.root); controls.pack(fill='x')
        ttk.Button(controls, text='提交脱敏凭据并销毁窗口', command=self.submit).pack(side='left')
        ttk.Button(controls, text='中断（丢弃全部未提交断言）', command=self.cancel).pack(side='left')
        ttk.Label(self.root, textvariable=self.message, wraplength=1050).pack(anchor='w')

    @staticmethod
    def view(parent, content, height):
        panel = ttk.Frame(parent); panel.pack(fill='both', expand=True)
        text = tk.Text(panel, wrap='word', height=height, exportselection=False)
        scroll = ttk.Scrollbar(panel, command=text.yview); scroll.pack(side='right', fill='y')
        text.configure(yscrollcommand=scroll.set)
        text.insert('1.0', content); text.configure(state='disabled'); text.pack(fill='both', expand=True)
        for event in ('<<Copy>>', '<<Cut>>', '<<Paste>>', '<Control-c>', '<Control-C>', '<Control-Insert>', '<Shift-Insert>'):
            text.bind(event, lambda _: 'break')
        return text

    @staticmethod
    def combo(parent, label, values, default=None):
        ttk.Label(parent, text=label).pack(side='left')
        var = tk.StringVar(value=default or values[0])
        ttk.Combobox(parent, state='readonly', values=values, textvariable=var, width=15).pack(side='left')
        return var

    def guard(self, action):
        try:
            action()
            self.message.set('已记录本机选择；语义与绑定仍由人工核验。')
        except ReviewError as error:
            # Error messages come only from fixed codes in review.py.
            code = str(error)
            self.message.set(code if code in ('INVALID_REVIEW', 'INCOMPLETE_REVIEW', 'SAVE_FAILED',
                                             'SELECT_ANSWER_RANGE', 'UNSAVED_DRAFT') else 'REVIEW_ERROR')
        except Exception:
            self.message.set('INVALID_REVIEW')

    def selected(self):
        try:
            start, end = self.answer.index('sel.first'), self.answer.index('sel.last')
            return [len(self.answer.get('1.0', start)), len(self.answer.get('1.0', end))]
        except tk.TclError:
            raise ReviewError('SELECT_ANSWER_RANGE') from None

    def show_source(self):
        self.source.configure(state='normal'); self.source.delete('1.0', 'end')
        text = self.session.context.sources[self.source_id.get()][1]
        self.source.insert('1.0', '\n'.join(f'{i}: {line}' for i, line in enumerate(text.splitlines(), 1)))
        self.source.configure(state='disabled')

    def select_claim(self):
        def action():
            self.ranges.append(self.selected()); self.ranges.sort()
            self.offsets.set(f'区间 {self.ranges}；引用标记 {self.marker}')
        self.guard(action)

    def select_marker(self):
        def action():
            self.marker = self.selected()
            self.offsets.set(f'区间 {self.ranges}；引用标记 {self.marker}')
        self.guard(action)

    def add_check(self):
        def action():
            self.checks.append({'sourceId': self.source_id.get(), 'startLine': int(self.first.get()), 'endLine': int(self.last.get())})
            self.draft_metadata.set(f'证据 {self.checks}；引用 {[x["citationId"] for x in self.links]}')
        self.guard(action)

    def add_link(self):
        self.links.append({'citationId': self.cid.get(), 'correspondence': self.correspondence.get(),
                           'supportType': self.link_type.get(), 'binding': self.binding.get(),
                           'markerSpan': list(self.marker) if self.marker else None})
        self.marker = None
        self.draft_metadata.set(f'证据 {self.checks}；引用 {[x["citationId"] for x in self.links]}')

    def add_claim(self):
        def action():
            number = self.session.add({'spans': self.ranges, 'kind': self.kind.get(),
                                      'coreIds': [e for e, v in self.core_checks.items() if v.get()],
                                      'support': self.support.get(), 'supportType': self.support_type.get(),
                                      'checks': self.checks, 'links': self.links,
                                      'unlinkedBinding': None if self.links else self.unlinked.get()})
            self.claim_list.insert('end', f'A{number} {self.kind.get()} {self.support.get()} {self.ranges}')
            self.reset()
        self.guard(action)

    def reset(self):
        self.ranges, self.checks, self.links, self.marker = [], [], [], None
        self.offsets.set('区间 []；引用标记无'); self.draft_metadata.set('证据 []；引用 []')
        for v in self.core_checks.values(): v.set(False)

    def undo(self):
        def action():
            self.session.undo(); self.claim_list.delete('end')
        self.guard(action)

    def submit(self):
        def action():
            if self.ranges or self.checks or self.links: raise ReviewError('UNSAVED_DRAFT')
            self.session.complete({'inventoryComplete': self.inventory.get(), 'secondPassComplete': self.second.get(),
                                   'coreDisposition': {e: v.get() for e, v in self.disposition.items()},
                                   'citationRelevance': {c: v.get() for c, v in self.relevance.items()},
                                   'abstentionVerdict': self.abstention.get() if self.abstention else 'NOT_APPLICABLE'})
            try: self.sink.save(self.session)
            except ReviewError:
                self.session.status = 'NOT_REVIEWED'; self.session._finish = None
                raise
            self.destroy()
        self.guard(action)

    def cancel(self):
        self.session.interrupt()
        try: self.sink.save(self.session)
        finally: self.destroy()

    def fail(self):
        # tkinter's default callback reporter prints tracebacks; never use it.
        try: self.cancel()
        except Exception: pass  # Initial receipt remains; no body/traceback is printed.

    def destroy(self):
        self.closed = True
        self.root.destroy()

    def run(self):
        try: self.root.mainloop()
        finally:
            if not self.closed: self.cancel()
