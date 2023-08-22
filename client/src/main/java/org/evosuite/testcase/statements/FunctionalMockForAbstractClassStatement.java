package org.evosuite.testcase.statements;

import org.evosuite.testcase.TestCase;
import org.evosuite.testcase.fm.EvoAbstractMethodInvocationListener;
import org.evosuite.testcase.fm.EvoInvocationListener;
import org.evosuite.testcase.fm.MethodDescriptor;
import org.evosuite.testcase.variable.VariableReference;
import org.evosuite.utils.generic.GenericClass;
import org.mockito.MockSettings;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;


import java.lang.reflect.Type;
import java.util.Map;

import static org.mockito.Mockito.CALLS_REAL_METHODS;
import static org.mockito.Mockito.withSettings;

public class FunctionalMockForAbstractClassStatement extends FunctionalMockStatement {

    private static final long serialVersionUID = -3933543503326450446L;

    private static final Logger logger = LoggerFactory.getLogger(FunctionalMockForAbstractClassStatement.class);

    public FunctionalMockForAbstractClassStatement(TestCase tc, VariableReference retval, GenericClass targetClass) throws IllegalArgumentException {
        super(tc, retval, targetClass);
    }

    public FunctionalMockForAbstractClassStatement(TestCase tc, Type retvalType, GenericClass targetClass) throws IllegalArgumentException {
        super(tc, retvalType, targetClass);
    }

    protected void checkTarget() {
        if(! canBeFunctionalMockedIncludingSUT(targetClass.getRawClass())){
            throw new IllegalArgumentException("Cannot create a basic functional mock for class "+targetClass);
        }
    }


    protected EvoInvocationListener createInvocationListener() {
        return new EvoAbstractMethodInvocationListener(retval.getGenericClass());
    }

    protected MockSettings createMockSettings() {
        return withSettings().defaultAnswer(CALLS_REAL_METHODS).invocationListeners(listener);
    }

    @Override
    public Statement copy(TestCase newTestCase, int offset) {


        FunctionalMockForAbstractClassStatement copy = new FunctionalMockForAbstractClassStatement(
                newTestCase, retval.getType(), new GenericClass(targetClass));

        for (VariableReference r : this.parameters) {
            copy.parameters.add(r.copy(newTestCase, offset));
        }

        if (this.listener != null) {
            copy.listener = this.listener.copy();
        }

        for (MethodDescriptor md : this.mockedMethods) {
            copy.mockedMethods.add(md.getCopy());
        }

        for (Map.Entry<String, int[]> entry : methodParameters.entrySet()) {
            int[] array = entry.getValue();
            int[] copiedArray = array == null ? null : new int[]{array[0], array[1]};
            copy.methodParameters.put(entry.getKey(), copiedArray);
        }

        return copy;
    }
}
