package com.fartech.sfl.ide.navigation;

import com.fartech.sfl.ide.SflFileType;
import com.intellij.openapi.application.ReadAction;
import com.intellij.openapi.editor.Document;
import com.intellij.openapi.progress.ProcessCanceledException;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vfs.LocalFileSystem;
import com.intellij.openapi.vfs.VirtualFile;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiManager;
import com.redhat.devtools.lsp4ij.LSPFileSupport;
import com.redhat.devtools.lsp4ij.LSPIJUtils;
import com.redhat.devtools.lsp4ij.features.implementation.LSPImplementationParams;
import com.redhat.devtools.lsp4ij.features.typeDefinition.LSPTypeDefinitionParams;
import com.redhat.devtools.lsp4ij.internal.CompletableFutures;
import com.redhat.devtools.lsp4ij.usages.LocationData;
import org.eclipse.lsp4j.Location;
import org.eclipse.lsp4j.Position;
import org.eclipse.lsp4j.TextDocumentIdentifier;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.net.URI;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

/**
 * Bridges the platform's native navigation — Go to Implementation(s), Go to
 * Type Declaration, their shortcuts and menu entries — to the language server,
 * through the very request plumbing LSP4IJ's own "LSP …" actions use. The
 * results come back as real PSI leaves in the target files, so the platform's
 * popups and navigation behave exactly as they do for any other language.
 */
public final class SflLspNavigation {

    private SflLspNavigation() {
    }

    public static boolean isSflElement(@Nullable PsiElement element) {
        if (element == null) {
            return false;
        }
        PsiFile file = element.getContainingFile();
        return file != null && file.getFileType() == SflFileType.INSTANCE;
    }

    /** textDocument/implementation for the element's position. */
    public static @NotNull List<PsiElement> implementations(@NotNull PsiElement element) {
        PsiFile file = element.getContainingFile();
        Document document = LSPIJUtils.getDocument(file.getVirtualFile());
        if (document == null) {
            return List.of();
        }
        int offset = element.getTextRange().getStartOffset();
        Position position = LSPIJUtils.toPosition(offset, document);
        TextDocumentIdentifier id = new TextDocumentIdentifier(LSPIJUtils.toUriAsString(file));
        CompletableFuture<List<LocationData>> future = LSPFileSupport.getSupport(file)
                .getImplementationSupport()
                .getImplementations(new LSPImplementationParams(id, position, offset));
        return toElements(file, await(future, file));
    }

    /** textDocument/typeDefinition for the element's position. */
    public static @NotNull List<PsiElement> typeDefinitions(@NotNull PsiElement element) {
        PsiFile file = element.getContainingFile();
        Document document = LSPIJUtils.getDocument(file.getVirtualFile());
        if (document == null) {
            return List.of();
        }
        int offset = element.getTextRange().getStartOffset();
        Position position = LSPIJUtils.toPosition(offset, document);
        TextDocumentIdentifier id = new TextDocumentIdentifier(LSPIJUtils.toUriAsString(file));
        CompletableFuture<List<LocationData>> future = LSPFileSupport.getSupport(file)
                .getTypeDefinitionSupport()
                .getTypeDefinitions(new LSPTypeDefinitionParams(id, position, offset));
        return toElements(file, await(future, file));
    }

    private static @NotNull List<LocationData> await(CompletableFuture<List<LocationData>> future, PsiFile file) {
        try {
            CompletableFutures.waitUntilDone(future, file);
        } catch (ProcessCanceledException e) {
            throw e;
        } catch (ExecutionException | RuntimeException e) {
            return List.of();
        }
        if (!CompletableFutures.isDoneNormally(future)) {
            return List.of();
        }
        List<LocationData> data = future.getNow(null);
        return data == null ? List.of() : data;
    }

    /** The PSI leaf at each location's start, in whichever file it lives. */
    private static @NotNull List<PsiElement> toElements(@NotNull PsiFile from, @NotNull List<LocationData> data) {
        Project project = from.getProject();
        List<PsiElement> out = new ArrayList<>();
        for (LocationData d : data) {
            Location location = d.location();
            if (location == null || location.getUri() == null) {
                continue;
            }
            PsiElement target = ReadAction.compute(() -> {
                VirtualFile vf = findFile(location.getUri());
                if (vf == null) {
                    return null;
                }
                PsiFile psi = PsiManager.getInstance(project).findFile(vf);
                Document doc = psi == null ? null : LSPIJUtils.getDocument(vf);
                if (psi == null || doc == null) {
                    return null;
                }
                int offset = Math.max(0, Math.min(
                        LSPIJUtils.toOffset(location.getRange().getStart(), doc), doc.getTextLength()));
                PsiElement leaf = psi.findElementAt(offset);
                return leaf != null ? leaf : psi;
            });
            if (target != null && !out.contains(target)) {
                out.add(target);
            }
        }
        return out;
    }

    private static @Nullable VirtualFile findFile(@NotNull String uri) {
        try {
            URI parsed = URI.create(uri);
            if ("file".equals(parsed.getScheme())) {
                return LocalFileSystem.getInstance().findFileByNioFile(Paths.get(parsed));
            }
        } catch (IllegalArgumentException ignored) {
            // fall through to the generic lookup
        }
        return com.intellij.openapi.vfs.VirtualFileManager.getInstance().findFileByUrl(uri);
    }
}
