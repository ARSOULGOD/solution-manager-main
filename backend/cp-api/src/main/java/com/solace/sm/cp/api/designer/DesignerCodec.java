package com.solace.sm.cp.api.designer;

import java.util.Map;

/**
 * The JSON edge of the designer port (plan 010 T5.3): the two conversions the console gateway needs and cannot do
 * for itself, because {@code cp-api} carries no Jackson and the gateway may not reach {@code cp-core}.
 *
 * <ul>
 *   <li>{@link #readPackage} binds a stored Solution Package document to {@link PackageModel.SolutionPackage}
 *       (the one {@code default} -&gt; {@code defaultValue} rename the dependency-free records cannot express).</li>
 *   <li>{@link #viewModelTree} is the ONLY way a {@link ViewModel} reaches the wire. It returns the ordered
 *       {@code Map}/{@code List} tree whose key order is the order {@code project.ts} builds its objects in, which is
 *       the byte contract {@code OR-34} locks on the 33 shared fixtures. Jackson over the {@code ViewModel} records
 *       would emit {@code ActionView.enabled} before {@code visible} and has no {@code FieldValue} discriminator, so
 *       a caller must never serialize the records directly.</li>
 * </ul>
 *
 * <p>Pure, stateless and thread-safe.
 */
public interface DesignerCodec {

    /**
     * @throws IllegalArgumentException the text is not a JSON object bound to a package. The message never quotes the
     *                                  document (a package may carry a secret-shaped default, {@code I6}).
     */
    PackageModel.SolutionPackage readPackage(String packageDocumentJson);

    /**
     * The view model as an ordered tree a JSON writer emits as it iterates: keys in the TS engine's order, an optional
     * property omitted rather than {@code null}, a required-nullable one ({@code packageVersion},
     * {@code renderedPath}) an explicit {@code null}.
     */
    Map<String, Object> viewModelTree(ViewModel viewModel);
}
