package com.solace.sm.cp.designer;

import com.solace.sm.cp.api.designer.DesignerCodec;
import com.solace.sm.cp.api.designer.PackageModel;
import com.solace.sm.cp.api.designer.ViewModel;
import java.io.IOException;
import java.util.Map;
import java.util.Objects;

/**
 * {@link DesignerCodec} over the two classes that already do the work: {@link PackageJsonReader} for the package
 * binding and {@link ViewModelJson} for the ordered tree (plan 010 T5.3). Neither is re-implemented here, so the byte
 * contract T1.2 proved on {@code ViewModelJson} is the one a served response carries.
 */
public final class DesignerCodecImpl implements DesignerCodec {

    @Override
    public PackageModel.SolutionPackage readPackage(String packageDocumentJson) {
        Objects.requireNonNull(packageDocumentJson, "packageDocumentJson");
        try {
            return PackageJsonReader.read(packageDocumentJson);
        } catch (IOException | RuntimeException e) {
            // Deliberately no cause and no message from it: Jackson quotes the offending text, and a package may carry
            // a secret-shaped default (I6).
            throw new IllegalArgumentException("the document is not a Solution Package");
        }
    }

    @Override
    public Map<String, Object> viewModelTree(ViewModel viewModel) {
        return ViewModelJson.of(Objects.requireNonNull(viewModel, "viewModel"));
    }
}
