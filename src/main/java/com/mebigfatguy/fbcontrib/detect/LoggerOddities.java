/*
 * fb-contrib - Auxiliary detectors for Java programs
 * Copyright (C) 2005-2016 Dave Brosius
 *
 * This library is free software; you can redistribute it and/or
 * modify it under the terms of the GNU Lesser General Public
 * License as published by the Free Software Foundation; either
 * version 2.1 of the License, or (at your option) any later version.
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the GNU
 * Lesser General Public License for more details.
 *
 * You should have received a copy of the GNU Lesser General Public
 * License along with this library; if not, write to the Free Software
 * Foundation, Inc., 59 Temple Place, Suite 330, Boston, MA  02111-1307  USA
 */
package com.mebigfatguy.fbcontrib.detect;

import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.bcel.Repository;
import org.apache.bcel.classfile.Code;
import org.apache.bcel.classfile.Constant;
import org.apache.bcel.classfile.ConstantClass;
import org.apache.bcel.classfile.ConstantPool;
import org.apache.bcel.classfile.ConstantUtf8;
import org.apache.bcel.classfile.JavaClass;
import org.apache.bcel.classfile.Method;

import com.mebigfatguy.fbcontrib.utils.BugType;
import com.mebigfatguy.fbcontrib.utils.OpcodeUtils;
import com.mebigfatguy.fbcontrib.utils.SignatureBuilder;
import com.mebigfatguy.fbcontrib.utils.SignatureUtils;
import com.mebigfatguy.fbcontrib.utils.TernaryPatcher;
import com.mebigfatguy.fbcontrib.utils.ToString;
import com.mebigfatguy.fbcontrib.utils.UnmodifiableSet;
import com.mebigfatguy.fbcontrib.utils.Values;

import edu.umd.cs.findbugs.BugInstance;
import edu.umd.cs.findbugs.BugReporter;
import edu.umd.cs.findbugs.BytecodeScanningDetector;
import edu.umd.cs.findbugs.OpcodeStack;
import edu.umd.cs.findbugs.OpcodeStack.CustomUserValue;
import edu.umd.cs.findbugs.OpcodeStack.Item;
import edu.umd.cs.findbugs.ba.ClassContext;

/**
 * looks for uses of log4j or slf4j where the class specified when creating the logger is not the same as the class in which this logger is used. Also looks for
 * using concatenation with slf4j logging rather than using the parameterized interface.
 */
@CustomUserValue
public class LoggerOddities extends BytecodeScanningDetector {

    private static final Set<String> LOGGER_METHODS = UnmodifiableSet.create("trace", "debug", "info", "warn", "error", "fatal");
    private static final String COMMONS_LOGGER = "org/apache/commons/logging/Log";
    private static final String LOG4J_LOGGER = "org/apache/log4j/Logger";
    private static final String SLF4J_LOGGER = "org/slf4j/Logger";
    private static final String SIG_STRING_AND_TWO_OBJECTS_TO_VOID = new SignatureBuilder().withParamTypes(Values.SLASHED_JAVA_LANG_STRING, Values.SLASHED_JAVA_LANG_OBJECT, Values.SLASHED_JAVA_LANG_OBJECT).toString();
    private static final String SIG_STRING_AND_OBJECT_ARRAY_TO_VOID = new SignatureBuilder().withParamTypes(Values.SLASHED_JAVA_LANG_STRING, SignatureBuilder.SIG_OBJECT_ARRAY).toString();
    private static final String SIG_OBJECT_AND_THROWABLE_TO_VOID = new SignatureBuilder().withParamTypes(Values.SLASHED_JAVA_LANG_OBJECT, Values.SLASHED_JAVA_LANG_THROWABLE).toString();
    private static final String SIG_STRING_AND_THROWABLE_TO_VOID = new SignatureBuilder().withParamTypes(Values.SLASHED_JAVA_LANG_STRING, Values.SLASHED_JAVA_LANG_THROWABLE).toString();
    private static final String SIG_CLASS_TO_COMMONS_LOGGER = new SignatureBuilder().withParamTypes(Values.SLASHED_JAVA_LANG_CLASS).withReturnType(COMMONS_LOGGER).toString();
    private static final String SIG_CLASS_TO_LOG4J_LOGGER = new SignatureBuilder().withParamTypes(Values.SLASHED_JAVA_LANG_CLASS).withReturnType(LOG4J_LOGGER).toString();
    private static final String SIG_CLASS_TO_SLF4J_LOGGER = new SignatureBuilder().withParamTypes(Values.SLASHED_JAVA_LANG_CLASS).withReturnType(SLF4J_LOGGER).toString();
    private static final String SIG_STRING_TO_COMMONS_LOGGER = new SignatureBuilder().withParamTypes(Values.SLASHED_JAVA_LANG_STRING).withReturnType(COMMONS_LOGGER).toString();
    private static final String SIG_STRING_TO_LOG4J_LOGGER = new SignatureBuilder().withParamTypes(Values.SLASHED_JAVA_LANG_STRING).withReturnType(LOG4J_LOGGER).toString();
    private static final String SIG_STRING_TO_SLF4J_LOGGER = new SignatureBuilder().withParamTypes(Values.SLASHED_JAVA_LANG_STRING).withReturnType(SLF4J_LOGGER).toString();
    private static final String SIG_STRING_AND_FACTORY_TO_LOG4J_LOGGER = new SignatureBuilder().withParamTypes(Values.SLASHED_JAVA_LANG_STRING, "org/apache/log4j/spi/LoggerFactory")
            .withReturnType(LOG4J_LOGGER).toString();

    private static final Pattern BAD_FORMATTING_ANCHOR = Pattern.compile("\\{[0-9]\\}");
    private static final Pattern BAD_STRING_FORMAT_PATTERN = Pattern
            .compile("%([0-9]*\\$)?(-|#|\\+| |0|,|\\(|)?[0-9]*(\\.[0-9]+)?(b|h|s|c|d|o|x|e|f|g|a|t|%|n)");
    private static final Pattern FORMATTER_ANCHOR = Pattern.compile("\\{\\}");
    private static final Pattern NON_SIMPLE_FORMAT = Pattern.compile(".*\\%[^sdf].*", Pattern.CASE_INSENSITIVE);

    private final BugReporter bugReporter;
    private JavaClass throwableClass;
    private OpcodeStack stack;
    private String nameOfThisClass;

    /**
     * constructs a LO detector given the reporter to report bugs on.
     *
     * @param bugReporter
     *            the sync of bug reports
     */
    public LoggerOddities(final BugReporter bugReporter) {
        this.bugReporter = bugReporter;

        try {
            throwableClass = Repository.lookupClass(Values.SLASHED_JAVA_LANG_THROWABLE);
        } catch (ClassNotFoundException cnfe) {
            bugReporter.reportMissingClass(cnfe);
        }

    }

    /**
     * implements the visitor to discover what the class name is if it is a normal class, or the owning class, if the class is an anonymous class.
     *
     * @param classContext
     *            the context object of the currently parsed class
     */
    @Override
    public void visitClassContext(ClassContext classContext) {
        try {
            stack = new OpcodeStack();
            nameOfThisClass = SignatureUtils.getNonAnonymousPortion(classContext.getJavaClass().getClassName());
            super.visitClassContext(classContext);
        } finally {
            stack = null;
        }
    }

    /**
     * implements the visitor to reset the stack
     *
     * @param obj
     *            the context object of the currently parsed code block
     */
    @Override
    public void visitCode(Code obj) {
        stack.resetForMethodEntry(this);
        Method m = getMethod();
        if (Values.CONSTRUCTOR.equals(m.getName())) {
            for (String parmSig : SignatureUtils.getParameterSignatures(m.getSignature())) {
                if (SignatureUtils.classToSignature(SLF4J_LOGGER).equals(parmSig) || SignatureUtils.classToSignature(LOG4J_LOGGER).equals(parmSig) || SignatureUtils.classToSignature(COMMONS_LOGGER).equals(parmSig)) {
                    bugReporter.reportBug(new BugInstance(this, BugType.LO_SUSPECT_LOG_PARAMETER.name(), NORMAL_PRIORITY).addClass(this).addMethod(this));
                }
            }
        }
        super.visitCode(obj);
    }

    /**
     * implements the visitor to look for calls to Logger.getLogger with the wrong class name
     *
     * @param seen
     *            the opcode of the currently parsed instruction
     */
    @Override
    @SuppressWarnings("unchecked")
    public void sawOpcode(int seen) {
        String ldcClassName = null;
        String seenMethodName = null;
        int exMessageReg = -1;
        Integer arraySize = null;
        Boolean simpleFormat = null;

        try {
            stack.precomputation(this);

            if ((seen == LDC) || (seen == LDC_W)) {
                Constant c = getConstantRefOperand();
                if (c instanceof ConstantClass) {
                    ConstantPool pool = getConstantPool();
                    ldcClassName = ((ConstantUtf8) pool.getConstant(((ConstantClass) c).getNameIndex())).getBytes();
                }
            } else if (seen == INVOKESTATIC) {
                lookForSuspectClasses();

                if (Values.SLASHED_JAVA_LANG_STRING.equals(getClassConstantOperand()) && "format".equals(getNameConstantOperand())
                        && (stack.getStackDepth() >= 2)) {
                    String format = (String) stack.getStackItem(1).getConstant();
                    if (format != null) {
                        Matcher m = NON_SIMPLE_FORMAT.matcher(format);
                        if (!m.matches()) {
                            simpleFormat = Boolean.TRUE;
                        }
                    }
                }
            } else if (((seen == INVOKEVIRTUAL) || (seen == INVOKEINTERFACE)) && (throwableClass != null)) {
                String mthName = getNameConstantOperand();
                if ("getName".equals(mthName)) {
                    if (stack.getStackDepth() >= 1) {
                        // Foo.class.getName() is being called, so we pass the
                        // name of the class to the current top of the stack
                        // (the name of the class is currently on the top of the
                        // stack, but won't be on the stack at all next opcode)
                        Item stackItem = stack.getStackItem(0);
                        LOUserValue<String> uv = (LOUserValue<String>) stackItem.getUserValue();
                        if ((uv != null) && (uv.getType() == LOUserValue.LOType.CLASS_NAME)) {
                            ldcClassName = uv.getValue();
                        }
                    }
                } else if ("getMessage".equals(mthName)) {
                    String callingClsName = getClassConstantOperand();
                    JavaClass cls = Repository.lookupClass(callingClsName);
                    if (cls.instanceOf(throwableClass) && (stack.getStackDepth() > 0)) {
                        OpcodeStack.Item exItem = stack.getStackItem(0);
                        exMessageReg = exItem.getRegisterNumber();
                    }
                } else if (LOGGER_METHODS.contains(mthName)) {
                    checkForProblemsWithLoggerMethods();
                } else if ("toString".equals(mthName)) {
                    String callingClsName = getClassConstantOperand();
                    if (SignatureUtils.isAppendableStringClassName(callingClsName) && (stack.getStackDepth() > 0)) {
                        OpcodeStack.Item item = stack.getStackItem(0);
                        // if the stringbuilder was previously stored, don't report it
                        if (item.getRegisterNumber() < 0) {
                            seenMethodName = mthName;
                        }
                    }
                }
            } else if (seen == INVOKESPECIAL) {
                checkForLoggerParam();
            } else if (seen == ANEWARRAY) {
                if (stack.getStackDepth() > 0) {
                    OpcodeStack.Item sizeItem = stack.getStackItem(0);
                    Object con = sizeItem.getConstant();
                    if (con instanceof Integer) {
                        arraySize = (Integer) con;
                    }
                }
            } else if (seen == AASTORE) {
                if (stack.getStackDepth() >= 3) {
                    OpcodeStack.Item arrayItem = stack.getStackItem(2);
                    LOUserValue<Integer> uv = (LOUserValue<Integer>) arrayItem.getUserValue();
                    if ((uv != null) && (uv.getType() == LOUserValue.LOType.ARRAY_SIZE)) {
                        Integer size = uv.getValue();
                        if ((size != null) && (size.intValue() > 0) && hasExceptionOnStack()) {
                            arrayItem.setUserValue(new LOUserValue<>(LOUserValue.LOType.ARRAY_SIZE, Integer.valueOf(-size.intValue())));
                        }
                    }
                }
            } else if (OpcodeUtils.isAStore(seen) && (stack.getStackDepth() > 0)) {
                OpcodeStack.Item item = stack.getStackItem(0);
                LOUserValue<String> uv = (LOUserValue<String>) item.getUserValue();
                if (uv != null) {
                    if (((uv.getType() == LOUserValue.LOType.METHOD_NAME) && "toString".equals(uv.getValue()))
                            || (uv.getType() == LOUserValue.LOType.SIMPLE_FORMAT)) {
                        item.setUserValue(new LOUserValue<>(LOUserValue.LOType.NULL, null));
                    }
                }
            }
        } catch (ClassNotFoundException cnfe) {
            bugReporter.reportMissingClass(cnfe);
        } finally {
            TernaryPatcher.pre(stack, seen);
            stack.sawOpcode(this, seen);
            TernaryPatcher.post(stack, seen);

            if (stack.getStackDepth() > 0) {
                OpcodeStack.Item item = stack.getStackItem(0);
                if (ldcClassName != null) {
                    item.setUserValue(new LOUserValue<>(LOUserValue.LOType.CLASS_NAME, ldcClassName));
                } else if (seenMethodName != null) {
                    item.setUserValue(new LOUserValue<>(LOUserValue.LOType.METHOD_NAME, seenMethodName));
                } else if (exMessageReg >= 0) {
                    item.setUserValue(new LOUserValue<>(LOUserValue.LOType.MESSAGE_REG, Integer.valueOf(exMessageReg)));
                } else if (arraySize != null) {
                    item.setUserValue(new LOUserValue<>(LOUserValue.LOType.ARRAY_SIZE, arraySize));
                } else if (simpleFormat != null) {
                    item.setUserValue(new LOUserValue<>(LOUserValue.LOType.SIMPLE_FORMAT, simpleFormat));
                }
            }
        }
    }

    /**
     * looks for a variety of logging issues with log statements
     *
     * @throws ClassNotFoundException
     *             if the exception class, or a parent class can't be found
     */
    @SuppressWarnings("unchecked")
    private void checkForProblemsWithLoggerMethods() throws ClassNotFoundException {
        String callingClsName = getClassConstantOperand();
        if (callingClsName.endsWith("Log") || (callingClsName.endsWith("Logger"))) {
            String sig = getSigConstantOperand();
            if (SIG_STRING_AND_THROWABLE_TO_VOID.equals(sig)
                    || SIG_OBJECT_AND_THROWABLE_TO_VOID.equals(sig)) {
                if (stack.getStackDepth() >= 2) {
                    OpcodeStack.Item exItem = stack.getStackItem(0);
                    OpcodeStack.Item msgItem = stack.getStackItem(1);

                    LOUserValue<Integer> uv = (LOUserValue<Integer>) msgItem.getUserValue();
                    if ((uv != null) && (uv.getType() == LOUserValue.LOType.MESSAGE_REG) && (uv.getValue().intValue() == exItem.getRegisterNumber())) {
                        bugReporter.reportBug(
                                new BugInstance(this, BugType.LO_STUTTERED_MESSAGE.name(), NORMAL_PRIORITY).addClass(this).addMethod(this).addSourceLine(this));
                    }
                }
            } else if (SignatureBuilder.SIG_OBJECT_TO_VOID.equals(sig)) {
                if (stack.getStackDepth() > 0) {
                    final JavaClass clazz = stack.getStackItem(0).getJavaClass();
                    if ((clazz != null) && clazz.instanceOf(throwableClass)) {
                        bugReporter.reportBug(new BugInstance(this, BugType.LO_LOGGER_LOST_EXCEPTION_STACK_TRACE.name(), NORMAL_PRIORITY).addClass(this)
                                .addMethod(this).addSourceLine(this));
                    }
                }
            } else if (SLF4J_LOGGER.equals(callingClsName)) {
                String signature = getSigConstantOperand();
                if (SignatureBuilder.SIG_STRING_TO_VOID.equals(signature)
                        || SignatureBuilder.SIG_STRING_AND_OBJECT_TO_VOID.equals(signature)
                        || SIG_STRING_AND_TWO_OBJECTS_TO_VOID.equals(signature)
                        || SIG_STRING_AND_OBJECT_ARRAY_TO_VOID.equals(signature)) {
                    int numParms = SignatureUtils.getNumParameters(signature);
                    if (stack.getStackDepth() >= numParms) {
                        OpcodeStack.Item formatItem = stack.getStackItem(numParms - 1);
                        Object con = formatItem.getConstant();
                        if (con instanceof String) {
                            Matcher m = BAD_FORMATTING_ANCHOR.matcher((String) con);
                            if (m.find()) {
                                bugReporter.reportBug(new BugInstance(this, BugType.LO_INVALID_FORMATTING_ANCHOR.name(), NORMAL_PRIORITY).addClass(this)
                                        .addMethod(this).addSourceLine(this));
                            } else {
                                m = BAD_STRING_FORMAT_PATTERN.matcher((String) con);
                                if (m.find()) {
                                    bugReporter.reportBug(new BugInstance(this, BugType.LO_INVALID_STRING_FORMAT_NOTATION.name(), NORMAL_PRIORITY)
                                            .addClass(this).addMethod(this).addSourceLine(this));
                                } else {
                                    int actualParms = getSLF4JParmCount(signature);
                                    if (actualParms != -1) {
                                        int expectedParms = countAnchors((String) con);
                                        boolean hasEx = hasExceptionOnStack();
                                        if ((!hasEx && (expectedParms != actualParms))
                                                || (hasEx && ((expectedParms != (actualParms - 1)) && (expectedParms != actualParms)))) {
                                            bugReporter
                                                    .reportBug(new BugInstance(this, BugType.LO_INCORRECT_NUMBER_OF_ANCHOR_PARAMETERS.name(), NORMAL_PRIORITY)
                                                            .addClass(this).addMethod(this).addSourceLine(this).addString("Expected: " + expectedParms)
                                                            .addString("Actual: " + actualParms));
                                        }
                                    }
                                }
                            }
                        } else {
                            LOUserValue<?> uv = (LOUserValue<?>) formatItem.getUserValue();
                            if ((uv != null) && (uv.getType() == LOUserValue.LOType.METHOD_NAME) && "toString".equals(uv.getValue())) {

                                bugReporter.reportBug(new BugInstance(this, BugType.LO_APPENDED_STRING_IN_FORMAT_STRING.name(), NORMAL_PRIORITY).addClass(this)
                                        .addMethod(this).addSourceLine(this));
                            } else {

                                if ((uv != null) && (uv.getType() == LOUserValue.LOType.SIMPLE_FORMAT)) {
                                    bugReporter
                                            .reportBug(new BugInstance(this, BugType.LO_EMBEDDED_SIMPLE_STRING_FORMAT_IN_FORMAT_STRING.name(), NORMAL_PRIORITY)
                                                    .addClass(this).addMethod(this).addSourceLine(this));
                                }
                            }
                        }
                    }
                }

            }
        }
    }

    /**
     * looks for slf4j calls where an exception is passed as a logger parameter, expecting to be substituted for a {} marker. As slf4j just passes the exception
     * down to the message generation itself, the {} marker will go unpopulated.
     */
    private void checkForLoggerParam() {
        if (Values.CONSTRUCTOR.equals(getNameConstantOperand())) {
            String cls = getClassConstantOperand();
            if ((cls.startsWith("java/") || cls.startsWith("javax/")) && cls.endsWith("Exception")) {
                String sig = getSigConstantOperand();
                List<String> types = SignatureUtils.getParameterSignatures(sig);
                if (types.size() <= stack.getStackDepth()) {
                    for (int i = 0; i < types.size(); i++) {
                        String parmSig = types.get(i);
                        if (Values.SIG_JAVA_LANG_STRING.equals(parmSig)) {
                            OpcodeStack.Item item = stack.getStackItem(types.size() - i - 1);
                            String cons = (String) item.getConstant();
                            if ((cons != null) && cons.contains("{}")) {
                                bugReporter.reportBug(new BugInstance(this, BugType.LO_EXCEPTION_WITH_LOGGER_PARMS.name(), NORMAL_PRIORITY).addClass(this)
                                        .addMethod(this).addSourceLine(this));
                                break;
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * looks for instantiation of a logger with what looks like a class name that isn't the same as the class in which it exists. There are some cases where a
     * 'classname-like' string is presented purposely different than this class, and an attempt is made to ignore those.
     */
    @SuppressWarnings("unchecked")
    private void lookForSuspectClasses() {
        String callingClsName = getClassConstantOperand();
        String mthName = getNameConstantOperand();

        String loggingClassName = null;
        int loggingPriority = NORMAL_PRIORITY;

        if ("org/slf4j/LoggerFactory".equals(callingClsName) && "getLogger".equals(mthName)) {
            String signature = getSigConstantOperand();

            if (SIG_CLASS_TO_SLF4J_LOGGER.equals(signature)) {
                loggingClassName = getLoggingClassNameFromStackValue();
            } else if (SIG_STRING_TO_SLF4J_LOGGER.equals(signature) && (stack.getStackDepth() > 0)) {
                OpcodeStack.Item item = stack.getStackItem(0);
                loggingClassName = (String) item.getConstant();
                loggingPriority = LOW_PRIORITY;
            }
        } else if (LOG4J_LOGGER.equals(callingClsName) && "getLogger".equals(mthName)) {
            String signature = getSigConstantOperand();

            if (SIG_CLASS_TO_LOG4J_LOGGER.equals(signature)) {
                loggingClassName = getLoggingClassNameFromStackValue();
            } else if (SIG_STRING_TO_LOG4J_LOGGER.equals(signature)) {
                if (stack.getStackDepth() > 0) {
                    OpcodeStack.Item item = stack.getStackItem(0);
                    loggingClassName = (String) item.getConstant();
                    LOUserValue<String> uv = (LOUserValue<String>) item.getUserValue();
                    if (uv != null) {
                        Object userValue = uv.getValue();

                        if (loggingClassName != null) {
                            // first look at the constant passed in
                            loggingPriority = LOW_PRIORITY;
                        } else if (userValue instanceof String) {
                            // try the user value, which may have been set by a call
                            // to Foo.class.getName()
                            loggingClassName = (String) userValue;
                        }
                    }
                }
            } else if (SIG_STRING_AND_FACTORY_TO_LOG4J_LOGGER.equals(signature) && (stack.getStackDepth() > 1)) {
                OpcodeStack.Item item = stack.getStackItem(1);
                loggingClassName = (String) item.getConstant();
                loggingPriority = LOW_PRIORITY;
            }
        } else if ("org/apache/commons/logging/LogFactory".equals(callingClsName) && "getLog".equals(mthName)) {
            String signature = getSigConstantOperand();

            if (SIG_CLASS_TO_COMMONS_LOGGER.equals(signature)) {
                loggingClassName = getLoggingClassNameFromStackValue();
            } else if (SIG_STRING_TO_COMMONS_LOGGER.equals(signature) && (stack.getStackDepth() > 0)) {
                OpcodeStack.Item item = stack.getStackItem(0);
                loggingClassName = (String) item.getConstant();
                loggingPriority = LOW_PRIORITY;
            }
        }

        if (loggingClassName != null) {
            loggingClassName = loggingClassName.replace('/', '.');
            if ((stack.getStackDepth() > 0) && !loggingClassName.equals(SignatureUtils.getNonAnonymousPortion(nameOfThisClass))) {
                bugReporter.reportBug(new BugInstance(this, BugType.LO_SUSPECT_LOG_CLASS.name(), loggingPriority).addClass(this).addMethod(this)
                        .addSourceLine(this).addString(loggingClassName).addString(nameOfThisClass));
            }
        }
    }

    private String getLoggingClassNameFromStackValue() {
        if (stack.getStackDepth() > 0) {
            OpcodeStack.Item item = stack.getStackItem(0);
            LOUserValue<String> uv = (LOUserValue<String>) item.getUserValue();
            if ((uv != null) && (uv.getType() == LOUserValue.LOType.CLASS_NAME)) {
                return uv.getValue();
            }
        }
        return null;
    }

    /**
     * returns the number of anchors {} in a string
     *
     * @param formatString
     *            the format string
     * @return the number of anchors
     */
    private static int countAnchors(String formatString) {
        Matcher m = FORMATTER_ANCHOR.matcher(formatString);
        int count = 0;
        int start = 0;
        while (m.find(start)) {
            ++count;
            start = m.end();
        }

        return count;
    }

    /**
     * returns the number of parameters slf4j is expecting to inject into the format string
     *
     * @param signature
     *            the method signature of the error, warn, info, debug statement
     * @return the number of expected parameters
     */
    @SuppressWarnings("unchecked")
    private int getSLF4JParmCount(String signature) {
        if (SignatureBuilder.SIG_STRING_AND_OBJECT_TO_VOID.equals(signature)) {
            return 1;
        }
        if (SIG_STRING_AND_TWO_OBJECTS_TO_VOID.equals(signature)) {
            return 2;
        }

        OpcodeStack.Item item = stack.getStackItem(0);
        LOUserValue<Integer> uv = (LOUserValue<Integer>) item.getUserValue();
        if ((uv != null) && (uv.getType() == LOUserValue.LOType.ARRAY_SIZE)) {
            Integer size = uv.getValue();
            if (size != null) {
                return Math.abs(size.intValue());
            }
        }
        return -1;
    }

    /**
     * returns whether an exception object is on the stack slf4j will find this, and not include it in the parm list so i we find one, just don't report
     *
     * @return whether or not an exception i present
     */
    @SuppressWarnings("unchecked")
    private boolean hasExceptionOnStack() {
        try {
            for (int i = 0; i < (stack.getStackDepth() - 1); i++) {
                OpcodeStack.Item item = stack.getStackItem(i);
                String sig = item.getSignature();
                if (sig.startsWith(Values.SIG_QUALIFIED_CLASS_PREFIX)) {
                    String name = SignatureUtils.stripSignature(sig);
                    JavaClass cls = Repository.lookupClass(name);
                    if (cls.instanceOf(throwableClass)) {
                        return true;
                    }
                } else if (sig.startsWith(Values.SIG_ARRAY_PREFIX)) {
                    LOUserValue<Integer> uv = (LOUserValue<Integer>) item.getUserValue();
                    if ((uv != null) && (uv.getType() == LOUserValue.LOType.ARRAY_SIZE)) {
                        Integer sz = uv.getValue();
                        if ((sz != null) && (sz.intValue() < 0)) {
                            return true;
                        }
                    }
                }
            }
            return false;
        } catch (ClassNotFoundException cnfe) {
            bugReporter.reportMissingClass(cnfe);
            return true;
        }
    }

    static class LOUserValue<T> {
        enum LOType {
            CLASS_NAME, METHOD_NAME, MESSAGE_REG, ARRAY_SIZE, SIMPLE_FORMAT, NULL
        };

        LOType type;
        T value;

        public LOUserValue(LOType type, T value) {
            this.type = type;
            this.value = value;
        }

        public LOType getType() {
            return type;
        }

        public T getValue() {
            return value;
        }

        @Override
        public String toString() {
            return ToString.build(this);
        }

    }
}
