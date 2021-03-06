package org.stjs.generator.writer.expression;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import javax.lang.model.element.Element;

import org.stjs.generator.GenerationContext;
import org.stjs.generator.javac.TreeUtils;
import org.stjs.generator.javac.TreeWrapper;
import org.stjs.generator.javascript.JavaScriptBuilder;
import org.stjs.generator.javascript.Keyword;
import org.stjs.generator.javascript.NameValue;
import org.stjs.generator.name.DependencyType;
import org.stjs.generator.writer.WriterContributor;
import org.stjs.generator.writer.WriterVisitor;
import org.stjs.generator.writer.declaration.MethodWriter;
import org.stjs.generator.writer.templates.MethodToPropertyTemplate;

import com.sun.source.tree.AssignmentTree;
import com.sun.source.tree.BlockTree;
import com.sun.source.tree.ExpressionStatementTree;
import com.sun.source.tree.ExpressionTree;
import com.sun.source.tree.IdentifierTree;
import com.sun.source.tree.MemberSelectTree;
import com.sun.source.tree.MethodInvocationTree;
import com.sun.source.tree.MethodTree;
import com.sun.source.tree.NewClassTree;
import com.sun.source.tree.StatementTree;
import com.sun.source.tree.Tree;

public class NewClassWriter<JS> implements WriterContributor<NewClassTree, JS> {
	public static BlockTree getDoubleBracesBlock(NewClassTree tree) {
		if (tree.getClassBody() == null) {
			return null;
		}
		for (Tree member : tree.getClassBody().getMembers()) {
			if (member instanceof BlockTree) {
				// XXX I should be sure it's not the one generated by the compiler
				BlockTree block = (BlockTree) member;
				if (!block.isStatic()) {
					return block;
				}
			}
		}
		return null;
	}

	private String getPropertyName(ExpressionTree var) {
		if (var instanceof IdentifierTree) {
			return ((IdentifierTree) var).getName().toString();
		}
		if (var instanceof MemberSelectTree) {
			return ((MemberSelectTree) var).getIdentifier().toString();
		}
		// TODO exception!?
		return null;
	}

	/**
	 * special construction for object initialization new Object(){{x = 1; y = 2; }};
	 */
	private JS getObjectInitializer(WriterVisitor<JS> visitor, TreeWrapper<NewClassTree, JS> tw) {
		NewClassTree tree = tw.getTree();
		BlockTree initBlock = getDoubleBracesBlock(tree);
		if (initBlock == null) {
			if (tw.child(tree.getIdentifier()).isSyntheticType()) {
				// the syntethic type will generate {} constructor even without the initBlock
				return tw.getContext().js().object(new ArrayList<NameValue<JS>>());
			}
			return null;
		}

		if (!tw.child(tree.getIdentifier()).isSyntheticType()) {
			// this is already checked and not allowed
			return null;
		}

		List<NameValue<JS>> props = new ArrayList<NameValue<JS>>();
		for (StatementTree stmt : initBlock.getStatements()) {
			// check the right type of statements x=y is done in NewClassObjectInitCheck
			ExpressionTree expr = ((ExpressionStatementTree) stmt).getExpression();

			if (expr instanceof AssignmentTree) {
				AssignmentTree assign = (AssignmentTree) expr;
				props.add(NameValue.of(getPropertyName(assign.getVariable()), visitor.scan(assign.getExpression(), tw.getContext())));

			} else {
				MethodInvocationTree meth = (MethodInvocationTree) expr;
				String propertyName = MethodToPropertyTemplate.getPropertyName(meth);
				JS value = visitor.scan(meth.getArguments().get(0), tw.getContext());
				props.add(NameValue.of(propertyName, value));
			}
		}
		return tw.getContext().js().object(props);
	}

	/**
	 * check by {@link org.stjs.generator.check.expression.NewClassInlineFunctionCheck} generate the code for inline
	 * functions:
	 *
	 * <pre>
	 * new FunctionInterface(){
	 * 	  public void $invoke(args){
	 *    }
	 * }
	 * </pre>
	 *
	 * is transformed in
	 *
	 * <pre>
	 * function(args){
	 * }
	 * </pre>
	 */
	private JS getInlineFunctionDeclaration(WriterVisitor<JS> visitor, TreeWrapper<NewClassTree, JS> tw) {
		// special construction for inline function definition
		if (!tw.child(tw.getTree().getIdentifier()).isJavaScriptFunction()) {
			return null;
		}

		// the check verifies the existence of a single method (first is the generated
		// constructor)
		Tree method = tw.getTree().getClassBody().getMembers().get(1);
		JS func = visitor.scan(method, tw.getContext());

		int specialThisParamPos = MethodWriter.getTHISParamPos(((MethodTree) method).getParameters());
		// accessOuterScope(visitor, tree, context) ||
		if (specialThisParamPos >= 0) {
			// bind for inline functions accessing the outher scope or with special this
			JavaScriptBuilder<JS> js = tw.getContext().js();
			JS target = js.keyword(Keyword.THIS);
			JS stjsBind = js.property(js.name("stjs"), "bind");
			return js.functionCall(stjsBind, Arrays.asList(target, func, js.number(specialThisParamPos)));
		}

		return func;
	}

	private JS getAnonymousClassDeclaration(WriterVisitor<JS> visitor, NewClassTree tree, GenerationContext<JS> context) {
		if (tree.getClassBody() == null) {
			return null;
		}

		JS typeDeclaration = visitor.scan(tree.getClassBody(), context);

		return context.js().newExpression(context.js().paren(typeDeclaration), arguments(visitor, tree, context));
	}

	private List<JS> arguments(WriterVisitor<JS> visitor, NewClassTree tree, GenerationContext<JS> context) {
		List<JS> arguments = new ArrayList<JS>();
		for (Tree arg : tree.getArguments()) {
			arguments.add(visitor.scan(arg, context));
		}
		return arguments;
	}

	private JS getRegularNewExpression(WriterVisitor<JS> visitor, NewClassTree tree, GenerationContext<JS> context) {
		Element type = TreeUtils.elementFromUse(tree.getIdentifier());
		JS typeName = context.js().name(context.getNames().getTypeName(context, type, DependencyType.STATIC));
		return context.js().newExpression(typeName, arguments(visitor, tree, context));
	}

	@Override
	public JS visit(WriterVisitor<JS> visitor, NewClassTree tree, GenerationContext<JS> context) {
		TreeWrapper<NewClassTree, JS> tw = context.getCurrentWrapper();
		JS js = getInlineFunctionDeclaration(visitor, tw);
		if (js != null) {
			return js;
		}

		js = getObjectInitializer(visitor, tw);
		if (js != null) {
			return js;
		}

		js = getAnonymousClassDeclaration(visitor, tree, context);
		if (js != null) {
			return js;
		}

		return getRegularNewExpression(visitor, tree, context);

		// if (clazz instanceof ClassWrapper && ClassUtils.isSyntheticType(clazz)) {
		// // this is a call to an mock type
		// printer.print("{}");
		// return;
		// }

	}

}
