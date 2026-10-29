import re
src=open("../app/src/main/java/com/pragon/mobile/V9Voice.kt").read()
env={};rx={}
for m in re.finditer(r'private (const )?val (\w+) = (Regex\()?"((?:[^"\\]|\\.)*)"\)?\n',src):
    v=m.group(4).replace('\\\\','\x00')
    for k in sorted(env,key=len,reverse=True): v=v.replace('$'+k,env[k])
    v=v.replace('\x00','\\')
    if m.group(1): env[m.group(2)]=v
    else: rx[m.group(2)]=re.compile(v)
def light(s):
    s=s.lower().replace('\u2019','').replace("'",'');s=re.sub(r'[^a-z0-9+*# ]',' ',s);return re.sub(r'\s+',' ',s).strip()
D={"zero":"0","oh":"0","one":"1","two":"2","three":"3","four":"4","five":"5","six":"6","seven":"7","eight":"8","nine":"9"}
def dialable(t):
    t=light(t)
    if not t:return None
    sb='';mult=1
    for tok in t.split(' '):
        if tok=='double':mult=2
        elif tok=='triple':mult=3
        elif tok=='plus':
            if sb:return None
            sb+='+'
        elif tok=='star':sb+='*';mult=1
        elif tok in('hash','pound'):sb+='#';mult=1
        elif tok in D:sb+=D[tok]*mult;mult=1
        elif all(c in '0123456789+*#' for c in tok):
            if '+' in tok and (sb or tok.index('+')!=0 or tok.rindex('+')!=0):return None
            sb+=tok*mult if(mult>1 and len(tok)==1) else tok;mult=1
        else:return None
    if mult!=1:return None
    return sb if len(sb)>=3 else None
def split(s0):
    s=rx['NAME_NOISE'].sub('',s0.strip(),count=1);s=rx['NUMBER_NOISE'].sub(' ',s);s=re.sub(r'\s+',' ',s).strip()
    t=[x for x in s.split(' ') if x]
    for i in range(1,len(t)):
        n=dialable(' '.join(t[i:]))
        if n and '*' not in n and '#' not in n:return ' '.join(t[:i]),n
    return ' '.join(t),''
def parse(raw):
    l0=light(raw)
    if not l0:return None
    l=rx['LEAD'].sub('',l0,count=1);named=rx['LEAD_P'].search(l) is not None;l=rx['LEAD_P'].sub('',l,count=1);l=rx['TRAIL'].sub('',l).strip()
    if not l:return None
    m=rx['CALL'].fullmatch(l)
    if m:
        t=m.group(1).strip()
        if t.startswith('number '):t=t[7:].strip()
        if t and not rx['NOT_A_TARGET'].search(t) and not rx['CHANNELS'].search(t):
            d=dialable(t)
            if d:return('call',d)
            if len(t.split(' '))<=3:return('call',t)
    m=rx['ADD'].fullmatch(l)
    if m:
        n,num=split(m.group(1))
        if n:return('pc_add',n+'|'+num)
    m=rx['REMOVE'].fullmatch(l)
    if m:
        n=rx['NAME_NOISE'].sub('',m.group(1).strip(),count=1).strip()
        if n:return('pc_remove',n)
    if rx['LIST'].fullmatch(l) or (named and rx['LIST_NAMED'].fullmatch(l)):return('pc_list','')
    for name,act in [('TOGGLE',('audio_route','toggle')),('SPEAKER',('audio_route','speaker')),('EARPIECE',('audio_route','earpiece')),('AUTO',('audio_route','auto')),('HMIC_ON',('headset_mic','on')),('HMIC_OFF',('headset_mic','off')),('PA_ON',('pa','on')),('PA_OFF',('pa','off')),('PA_STATUS',('pa','status')),('TAKEOVER',('pa','takeover')),('SUMMARY',('pa','summary'))]:
        if rx[name].fullmatch(l):return act
    return None
def dec(a):
    t=light(a)
    if rx['D_REJECT'].search(t):return'reject'
    if rx['D_IGNORE'].search(t):return'ignore'
    if rx['D_OK'].search(t):return'ok'
cases=[("call Ravi",('call','ravi')),("Call 98765 43210",('call','9876543210')),("dial +91 98765 43210",('call','+919876543210')),("call *123#",('call','*123#')),
("call number nine eight seven six five four three two one zero",('call','9876543210')),("hey pragon call ravi kumar please",('call','ravi kumar')),
("call me later",None),("call mom on whatsapp",None),("call back",None),("call the office",None),
("add Ravi 98765 43210 to Pragon contacts",('pc_add','ravi|9876543210')),("add ravi to pragon contacts",('pc_add','ravi|')),
("pragon add priya +91 98765 43210 to pragon contacts",('pc_add','priya|+919876543210')),("add ravi to contacts",None),
("add ravi number nine eight seven six five four three two one zero to pragon contacts",('pc_add','ravi|9876543210')),
("remove ravi from pragon contacts",('pc_remove','ravi')),("list pragon contacts",('pc_list','')),("pragon contacts",('pc_list','')),("show my contacts",None),
("use the speaker",('audio_route','speaker')),("speaker mode",('audio_route','speaker')),("turn on speakerphone",('audio_route','speaker')),("speaker",None),
("use the earpiece",('audio_route','earpiece')),("normal mode",('audio_route','earpiece')),("toggle the speaker",('audio_route','toggle')),
("use my airpods",('audio_route','auto')),("turn off the headset mic",('headset_mic','off')),("airpods mic on",('headset_mic','on')),
("turn on pragon pa",('pa','on')),("turn off pragon pa",('pa','off')),("pragon pa on",('pa','on')),("pragon takeover",('pa','takeover')),
("read my call summaries",('pa','summary')),("who called",('pa','summary')),("open youtube",None),("what is the weather",None),("volume up",None),("turn on wifi",None)]
bad=0
for t,e in cases:
    g=parse(t)
    if g!=e:bad+=1;print("FAIL",t,g,e)
print(len(cases),"cases, fail",bad)
for t,e in [("ok",'ok'),("okay attend it",'ok'),("ignore",'ignore'),("reject it",'reject'),("dont pick up",'reject'),("let it ring",'ignore'),("hmm",None)]:
    if dec(t)!=e:print("FAILD",t,dec(t))
print("done")
