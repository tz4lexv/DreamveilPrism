package dev.dreamveil.prism.graph;

/**
 * Describes a graph resource without exposing backend-native objects.
 * Imported resources are host-owned. Transient resources carry the complete
 * allocation description required by a backend.
 */
public record PrismResourceDescriptor(
        String name,
        PrismResourceType type,
        boolean imported,
        PrismTextureDesc textureDesc,
        PrismBufferDesc bufferDesc) {

    public PrismResourceDescriptor {
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("Resource name must not be blank");
        }
        if (type == null) {
            throw new IllegalArgumentException("Resource type must not be null");
        }

        if (imported) {
            if (textureDesc != null || bufferDesc != null) {
                throw new IllegalArgumentException("Imported resources must not declare Prism-owned allocation specs");
            }
        } else if (type == PrismResourceType.TEXTURE) {
            if (textureDesc == null || bufferDesc != null) {
                throw new IllegalArgumentException("Transient texture requires exactly one texture description");
            }
        } else if (type == PrismResourceType.BUFFER) {
            if (bufferDesc == null || textureDesc != null) {
                throw new IllegalArgumentException("Transient buffer requires exactly one buffer description");
            }
        }
    }

    public static PrismResourceDescriptor importedTexture(String name) {
        return new PrismResourceDescriptor(name, PrismResourceType.TEXTURE, true, null, null);
    }

    public static PrismResourceDescriptor importedBuffer(String name) {
        return new PrismResourceDescriptor(name, PrismResourceType.BUFFER, true, null, null);
    }

    public static PrismResourceDescriptor transientTexture(String name, PrismTextureDesc desc) {
        return new PrismResourceDescriptor(name, PrismResourceType.TEXTURE, false, desc, null);
    }

    public static PrismResourceDescriptor transientBuffer(String name, PrismBufferDesc desc) {
        return new PrismResourceDescriptor(name, PrismResourceType.BUFFER, false, null, desc);
    }

    /** Compatibility convenience for early Prism prototypes: full-resolution RGBA8 attachment. */
    public static PrismResourceDescriptor transientTexture(String name) {
        return transientTexture(name, PrismTextureDesc.colorAttachment(PrismTextureFormat.RGBA8_UNORM));
    }

    /** Compatibility convenience for early Prism prototypes: 256-byte copy-capable buffer. */
    public static PrismResourceDescriptor transientBuffer(String name) {
        return transientBuffer(name, new PrismBufferDesc(
                256,
                java.util.EnumSet.of(PrismBufferUsage.COPY_DST, PrismBufferUsage.COPY_SRC)));
    }

    public boolean physicallyCompatibleWith(PrismResourceDescriptor other) {
        if (other == null || imported || other.imported || type != other.type) {
            return false;
        }
        return switch (type) {
            case TEXTURE -> textureDesc.equals(other.textureDesc);
            case BUFFER -> bufferDesc.equals(other.bufferDesc);
        };
    }
}
