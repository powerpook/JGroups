package org.jgroups;

import org.jgroups.annotations.ManagedOperation;
import org.jgroups.blocks.MethodCall;
import org.jgroups.jmx.ResourceDMBean;
import org.jgroups.logging.Log;
import org.jgroups.logging.LogFactory;
import org.jgroups.stack.DiagnosticsHandler;
import org.jgroups.stack.Protocol;
import org.jgroups.util.Ref;
import org.jgroups.util.Util;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.*;
import java.util.stream.Stream;

/**
 * @author Bela Ban
 * @since  4.0
 */
public class JChannelProbeHandler implements DiagnosticsHandler.ProbeHandler {
    protected final JChannel      ch;
    protected final Log           log;

    public JChannelProbeHandler(JChannel ch) {
        this.ch=ch;
        log=LogFactory.getLog(ch.getClass());
    }

    public Map<String, String> handleProbe(String... keys) {
        Map<String,String> map=new TreeMap<>();
        for(String key : keys) {
            if(key.startsWith("jmx")) {
                handleJmx(map, key);
                continue;
            }
            if(key.startsWith("reset-stats")) {
                resetAllStats();
                continue;
            }
            if(key.startsWith("ops")) {
                listOperations(map, key);
                continue;
            }
            if(key.startsWith("invoke") || key.startsWith("op")) {
                int index=key.indexOf('=');
                if(index != -1) {
                    try {
                        handleOperation(map, key.substring(index + 1));
                    }
                    catch(Throwable throwable) {
                        log.error(Util.getMessage("OperationInvocationFailure"), key.substring(index + 1), throwable);
                    }
                }
                continue;
            }
            if(key.startsWith("threads")) {
                ThreadMXBean bean=ManagementFactory.getThreadMXBean();
                boolean cpu_supported=bean.isThreadCpuTimeSupported();
                boolean contention_supported=bean.isThreadContentionMonitoringSupported();
                int max_name=0;
                long[] ids=bean.getAllThreadIds();
                List<ThreadEntry> entries=new ArrayList<>(ids.length);

                for(long id : ids) {
                    ThreadInfo info=bean.getThreadInfo(id);
                    if(info == null) continue;
                    String thread_name=info.getThreadName();
                    max_name=Math.max(max_name, thread_name.length());
                    Thread.State state=info.getThreadState();
                    long blocked=info.getBlockedCount();
                    long blocked_time=contention_supported? info.getBlockedTime() : -1;
                    long waited=info.getWaitedCount();
                    long waited_time=contention_supported? info.getWaitedTime() : -1;
                    double cpu_time=cpu_supported? bean.getThreadCpuTime(id) : -1;
                    if(cpu_time > 0)
                        cpu_time/=1_000_000;
                    double user_time=cpu_supported? bean.getThreadUserTime(id) : -1;
                    if(user_time > 0)
                        user_time/=1_000_000;

                    ThreadEntry entry=new ThreadEntry(state, thread_name, blocked, waited, blocked_time, waited_time,
                                                      cpu_time, user_time);
                    entries.add(entry);
                }

                int index=key.indexOf('=');
                if(index >= 0) {
                    Comparator<ThreadEntry> comp=Comparator.comparing(e -> e.thread_name);
                    String val=key.substring(index+1);
                    if(val.startsWith("state"))
                        comp=Comparator.comparing(e -> e.state);
                    else if(val.startsWith("cpu"))
                        comp=Comparator.comparing((ThreadEntry e) -> e.cpu_time).reversed();
                    else if(val.startsWith("user"))
                        comp=Comparator.comparing((ThreadEntry e) -> e.user_time).reversed();
                    else if(val.startsWith("block"))
                        comp=Comparator.comparing((ThreadEntry e) -> e.blocks).reversed();
                    else if(val.startsWith("btime"))
                        comp=Comparator.comparing((ThreadEntry e) -> e.block_time).reversed();
                    else if(val.startsWith("wait"))
                        comp=Comparator.comparing((ThreadEntry e) -> e.waits).reversed();
                    else if(val.startsWith("wtime"))
                        comp=Comparator.comparing((ThreadEntry e) -> e.wait_time).reversed();
                    entries.sort(comp);
                }

                // see if we need to limit the displayed data
                index=key.indexOf('=', index+1);
                int limit=0;
                if(index >= 0) {
                    String val=key.substring(index+1);
                    limit=Integer.parseInt(val);
                }

                max_name=Math.min(max_name, 50)+1;
                String title="\n[%s]   \t%-" + max_name+"s: %10s %10s %6s %9s %10s %10s\n";
                String line="[%s]\t%-"+max_name+"s: %,8.0f %,8.0f %,10d %,9.0f %,10d %,10.0f\n";

                StringBuilder sb=new StringBuilder(String.format(title,
                                                                 "state", "thread-name", "cpu (ms)", "user (ms)",
                                                                 "block", "btime (ms)", "wait", "wtime (ms)"));
                Stream<ThreadEntry> stream=entries.stream();
                if(limit > 0)
                    stream=stream.limit(limit);
                stream.forEach(e -> sb.append(e.print(line)));
                map.put(key, sb.toString());
                continue;
            }
            if(key.equals("enable-cpu")) {
                map.put(key, enable(1, true));
                continue;
            }
            if(key.startsWith("enable-cont")) {
                map.put(key, enable(2, true));
                continue;
            }
            if(key.equals("disable-cpu")) {
                map.put(key, enable(1, false));
                continue;
            }
            if(key.startsWith("disable-cont")) {
                map.put(key, enable(2, false));
            }

            // everything else could be an attribute query (without prefix "jmx=") or an operation (without "op=")
            // https://issues.redhat.com/browse/JGRP-2413
            String protocol;
            int index=key.indexOf('.');
            if(index == -1)
                protocol=key;
            else
                protocol=key.substring(0, index);

            Protocol prot=ch.getProtocolStack().findProtocol(protocol);
            if(prot != null) {
                String tmp=key.substring(index+1);
                int left=tmp.indexOf('['), right=left != -1? tmp.indexOf(']', left) : -1;
                if(left != -1 && right != -1) { // it is most likely an operation
                    try {
                        return handleProbe("op=" + key);
                    }
                    catch(Throwable throwable) {
                        log.error(Util.getMessage("OperationInvocationFailure"), key.substring(index + 1), throwable);
                    }
                }
                else // try JMX
                    return handleProbe("jmx=" + key);
            }
        }
        return map;
    }

    public String[] supportedKeys() {
        return new String[]{"reset-stats", "jmx", "op=<operation>[<args>]", "ops",
          "threads[=<filter>[=<limit>]]", "enable-cpu", "enable-contention", "disable-cpu", "disable-contention"};
    }


    protected static String enable(int type, boolean flag) {
        ThreadMXBean bean=ManagementFactory.getThreadMXBean();
        boolean supported=false;
        if(type == 1) { // cpu
            supported=bean.isThreadCpuTimeSupported();
            if(supported)
                bean.setThreadCpuTimeEnabled(flag);
        }
        else if(type == 2) {
            supported=bean.isThreadContentionMonitoringSupported();
            if(supported)
                bean.setThreadContentionMonitoringEnabled(flag);
        }
        String tmp=type == 1? "CPU" : "contention";
        return String.format("%s monitoring supported: %b, %s monitoring enabled: %b", tmp, supported, tmp, supported && flag);
    }

    protected JChannel resetAllStats() {
        List<Protocol> prots=ch.getProtocolStack().getProtocols();
        prots.forEach(Protocol::resetStatistics);
        return ch;
    }

    /**
     * Dumps the attributes and their values of _all_ protocols in a stack
     * @return A map of protocol names as keys and maps (of attribute names and values) as values
     */
    protected Map<String,Map<String,Object>> dumpAttrsAllProtocols() {
        return ch.dumpStats();
    }

    /**
     * Dumps attributes and their values of a given protocol.
     * @param protocol_name The name of the protocol
     * @param attrs A list of attributes that need to be returned. If null, all attributes of the given protocol will
     *              be returned
     * @return A map of protocol names as keys and maps (of attribute names and values) as values
     */
    protected Map<String,Map<String,Object>> dumpAttrsSelectedProtocol(String protocol_name, List<String> attrs) {
        return ch.dumpStats(protocol_name, attrs);
    }



    protected void handleJmx(Map<String,String> map, String input) {
        int index=input.indexOf('=');
        if(index == -1) {
            Map<String,Map<String,Object>> tmp_stats=dumpAttrsAllProtocols();
            convert(tmp_stats, map); // inserts into map
            return;
        }
        String protocol_name=input.substring(index +1);
        index=protocol_name.indexOf('.');
        if(index == -1) {
            Map<String,Map<String,Object>> tmp_stats=dumpAttrsSelectedProtocol(protocol_name, null);
            convert(tmp_stats, map);
            return;
        }
        String rest=protocol_name;
        protocol_name=protocol_name.substring(0, index);
        String attrs=rest.substring(index +1); // e.g. "num_sent,msgs,num_received_msgs"
        List<String> list=Util.parseStringList(attrs, ",");

        // check if there are any attribute-sets in the list
        for(Iterator<String> it=list.iterator(); it.hasNext();) {
            String tmp=it.next();
            index=tmp.indexOf('=');
            if(index > -1) { // an attribute write
                it.remove();
                String attrname=tmp.substring(0, index);
                String attrvalue=tmp.substring(index+1);
                try {
                    handleAttrWrite(protocol_name, attrname, attrvalue);
                }
                catch(Exception e) {
                    log.error("failed writing: %s", e.toString());
                }
            }
        }
        if(!list.isEmpty()) {
            Map<String,Map<String,Object>> tmp_stats=dumpAttrsSelectedProtocol(protocol_name, list);
            convert(tmp_stats, map);
        }
    }


    protected void listOperations(Map<String, String> map, String key) {
        if(!key.contains("=")) {
            map.put("ops", listAllOperations());
            return;
        }
        String p=key.substring(key.indexOf("=")+1).trim();
        try {
            Class<? extends Protocol> cl=Util.loadProtocolClass(p, getClass());
            StringBuilder sb=new StringBuilder();
            listAllOperations(sb, cl);
            map.put("ops", sb.toString());
        }
        catch(Exception e) {
            log.warn("%s: protocol %s not found", ch.getAddress(), p);
        }
    }

    protected String listAllOperations() {
        StringBuilder sb=new StringBuilder();
        for(Protocol p: ch.getProtocolStack().getProtocols()) {
            listAllOperations(sb, p.getClass());
        }

        return sb.toString();
    }

    protected static void listAllOperations(StringBuilder sb, Class<?> cl) {
        sb.append(cl.getSimpleName()).append(":\n");
        Method[] methods=Util.getAllDeclaredMethodsWithAnnotations(cl, ManagedOperation.class);
        for(Method m: methods)
            sb.append("  ").append(methodToString(m)).append("\n");

        Util.forAllComponentTypes(cl, (clazz, prefix) -> {
            Method[] meths=Util.getAllDeclaredMethodsWithAnnotations(clazz, ManagedOperation.class);
            for(Method m: meths)
                sb.append("  ").append(prefix).append(".").append(methodToString(m)).append("\n");
        });
    }


    protected static String methodToString(Method m) {
        StringBuilder sb=new StringBuilder(m.getName());
        sb.append('(');
        StringJoiner sj = new StringJoiner(",");
        for (Class<?> parameterType : m.getParameterTypes()) {
            sj.add(parameterType.getTypeName());
        }
        sb.append(sj);
        sb.append(')');
        return sb.toString();
    }

    /**
     * Invokes an operation and puts the return value into map
     * @param map
     * @param operation Protocol.OperationName[args], e.g. STABLE.foo[arg1 arg2 arg3]
     */
    protected void handleOperation(Map<String, String> map, String operation) throws Exception {
        int index=operation.indexOf('.');
        if(index == -1)
            throw new IllegalArgumentException("operation " + operation + " is missing the protocol name");
        String prot_name=operation.substring(0, index);

        Protocol prot=null;
        try {
            Class<? extends Protocol> cl=Util.loadProtocolClass(prot_name, this.getClass());
            prot=ch.getProtocolStack().findProtocol(cl);
        }
        catch(Exception e) {
        }

        if(prot == null)
            prot=ch.getProtocolStack().findProtocol(prot_name);
        if(prot == null) {
            log.error("protocol %s not found", prot_name);
            return; // less drastic than throwing an exception...
        }

        int args_index=operation.indexOf('[');
        String method_name;
        if(args_index != -1)
            method_name=operation.substring(index +1, args_index).trim();
        else
            method_name=operation.substring(index+1).trim();

        String[] args=null;
        if(args_index != -1) {
            int end_index=operation.indexOf(']');
            if(end_index == -1)
                throw new IllegalArgumentException("] not found");
            List<String> str_args=Util.parseCommaDelimitedStrings(operation.substring(args_index + 1, end_index));
            Object[] strings=str_args.toArray();
            args=new String[strings.length];
            for(int i=0; i < strings.length; i++)
                args[i]=(String)strings[i];
        }

        Ref<Object> target=new Ref<>(prot);
        Ref<Method> method=new Ref<>(Util.findMethod(prot.getClass(), method_name, args));
        if(!method.isSet()) {
            final String[] arguments=args;
            // check if any of the components in this class (if it has any) has the method
            Util.forAllComponents(prot, (o,prefix) -> {
                if(!method.isSet() && method_name.startsWith(prefix + ".")) {
                    String m=method_name.substring(prefix.length() +1);
                    try {
                        Method meth=Util.findMethod(o.getClass(), m, arguments);
                        if(meth != null) {
                            method.set(meth);
                            target.set(o);
                        }
                    }
                    catch(Exception e) {
                    }
                }
            });
        }
        if(!method.isSet())
            throw new IllegalArgumentException(String.format("method %s not found in %s", method_name, prot.getName()));
        Object retval=invoke(method.get(), target.get(), args);
        if(retval != null)
            map.put(prot.getName() + "." + method_name, retval.toString());
    }

    protected static Object invoke(Method method, Object target, String[] args) throws Exception {
        MethodCall call=new MethodCall(method);
        Object[] converted_args=null;
        if(args != null) {
            converted_args=new Object[args.length];
            Class<?>[] types=method.getParameterTypes();
            for(int i=0; i < args.length; i++)
                converted_args[i]=Util.convert(args[i], types[i]);
        }
        return call.invoke(target, converted_args);
    }

    protected Method findMethod(Protocol prot, String method_name, String[] args) throws Exception {
        return null; // not used atm, but subclass needs to be changed before we can remove this method
    }

    protected void handleAttrWrite(String protocol_name, String attr_name, String attr_value) throws Exception {
        final Object target=ch.getProtocolStack().findProtocol(protocol_name);
        if(target == null) {
            log.error("protocol %s not found", protocol_name);
            return;
        }

        Ref<Exception> ex=new Ref<>(null);
        // first try attributes at the protocol level
        try {
            handleAttrWrite(target, attr_name, attr_value);
            return;
        }
        catch(Exception e) {
            ex.set(e);
        }

        // if none are found, try components next
        Util.forAllComponents(target, (comp,prefix) -> {
            if(attr_name.startsWith(prefix + ".")) {
                String actual_attrname=attr_name.substring(prefix.length()+1);
                try {
                    handleAttrWrite(comp, actual_attrname, attr_value);
                    ex.set(null);
                }
                catch(Exception e) {
                    ex.set(e);
                }
            }
        });

        if(ex.isSet())
            throw ex.get();
    }


    protected static void handleAttrWrite(Object target, String attr_name, String attr_value) {

        // 1. Try to find a field first
        Exception e1=null, e2=null;
        Field field=Util.getField(target.getClass(), attr_name);
        if(field != null) {
            Object value=Util.convert(attr_value, field.getType());
            if(value != null) {
                try {
                    Util.setField(field, target, value);
                    return;
                }
                catch(Exception ex) {
                    e1=ex;
                }
            }
        }

        // 2. Try to find a setter for X, e.g. x(type-of-x) or setX(type-of-x)
        ResourceDMBean.Accessor setter=ResourceDMBean.findSetter(target, attr_name);
        if(setter != null) {
            try {
                Class<?> type=setter instanceof ResourceDMBean.FieldAccessor?
                  ((ResourceDMBean.FieldAccessor)setter).getField().getType() :
                  setter instanceof ResourceDMBean.MethodAccessor?
                    ((ResourceDMBean.MethodAccessor)setter).getMethod().getParameterTypes()[0] : null;
                Object converted_value=Util.convert(attr_value, type);
                setter.invoke(converted_value);
                return;
            }
            catch(Exception ex) {
                e2=ex;
            }
        }

        // at this point, we could neither find a field (and set it successfully) nor find a setter
        // (and invoke it successfully)

        String s=String.format("failed setting %s to %s: %s", attr_name, attr_value,
                               (e1 != null || e2 != null)? e1 + " " + e2 : "field or setter not found");
        throw new IllegalArgumentException(s);
    }


    protected static void convert(Map<String,Map<String,Object>> in, Map<String,String> out) {
        if(in != null)
            in.entrySet().stream().filter(e -> e.getValue() != null).forEach(e -> out.put(e.getKey(), e.getValue().toString()));
    }


    protected static class ThreadEntry {
        protected final Thread.State state;
        protected final String       thread_name;
        protected final long         blocks, waits;
        protected final double       block_time, wait_time;  // ms
        protected final double       cpu_time, user_time;    // ms

        public ThreadEntry(Thread.State state, String thread_name, long blocks, long waits, double block_time, double wait_time,
                           double cpu_time, double user_time) {
            this.state=state;
            this.thread_name=thread_name;
            this.blocks=blocks;
            this.waits=waits;
            this.block_time=block_time;
            this.wait_time=wait_time;
            this.cpu_time=cpu_time;
            this.user_time=user_time;
        }

        public String toString() {
            return String.format("[%s] %s: blocks=%d (%.2f ms) waits=%d (%.2f ms) sys=%.2f ms user=%.2f ms\n",
                                 state, thread_name, blocks, block_time, waits, wait_time, cpu_time, user_time);
        }

        protected String print(String format) {
            return String.format(format, state, thread_name, cpu_time, user_time, blocks, block_time, waits, wait_time);
        }


    }

}
