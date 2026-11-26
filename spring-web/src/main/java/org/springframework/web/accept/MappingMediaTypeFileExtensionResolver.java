/*
 * Copyright 2002-2023 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.web.accept;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.CopyOnWriteArrayList;

import org.springframework.http.MediaType;
import org.springframework.lang.Nullable;

/**
 * An implementation of {@code MediaTypeFileExtensionResolver} that maintains
 * lookups between file extensions and MediaTypes in both directions.
 *
 * <p>Initially created with a map of file extensions and media types.
 * Subsequently, subclasses can use {@link #addMapping} to add more mappings.
 *
 * @author Rossen Stoyanchev
 * @author Juergen Hoeller
 * @since 3.2
 */
public class MappingMediaTypeFileExtensionResolver implements MediaTypeFileExtensionResolver {

	private final ConcurrentMap<String, MediaType> mediaTypes = new ConcurrentHashMap<>(64);

	private final ConcurrentMap<MediaType, List<String>> fileExtensions = new ConcurrentHashMap<>(64);

	private final List<String> allFileExtensions = new CopyOnWriteArrayList<>();

	/**
	 * Reverse lookup for plain file extensions that were resolved dynamically
	 * (i.e. not registered), kept separate from the registered mappings.
	 */
	private final ConcurrentMap<MediaType, CopyOnWriteArrayList<String>> resolvedFileExtensions = new ConcurrentHashMap<>(64);


	/**
	 * Create an instance with the given map of file extensions and media types.
	 */
	public MappingMediaTypeFileExtensionResolver(@Nullable Map<String, MediaType> mediaTypes) {
		if (mediaTypes != null) {
			Set<String> allFileExtensions = new HashSet<>(mediaTypes.size());
			mediaTypes.forEach((extension, mediaType) -> {
				String lowerCaseExtension = extension.toLowerCase(Locale.ROOT);
				this.mediaTypes.put(lowerCaseExtension, mediaType);
				addFileExtension(mediaType, lowerCaseExtension);
				allFileExtensions.add(lowerCaseExtension);
			});
			this.allFileExtensions.addAll(allFileExtensions);
		}
	}


	public Map<String, MediaType> getMediaTypes() {
		return this.mediaTypes;
	}

	protected List<MediaType> getAllMediaTypes() {
		return new ArrayList<>(this.mediaTypes.values());
	}

	/**
	 * Map an extension to a MediaType. Ignore if extension already mapped.
	 */
	protected void addMapping(String extension, MediaType mediaType) {
		MediaType previous = this.mediaTypes.putIfAbsent(extension, mediaType);
		if (previous == null) {
			addFileExtension(mediaType, extension);
			this.allFileExtensions.add(extension);
		}
	}

	/**
	 * Remember a dynamically resolved file extension for reverse lookups via
	 * {@link #resolveFileExtensions}, without registering it as a mapping.
	 * Only plain file extensions (no '.') are kept, in lower case, so the
	 * number of entries is bounded by the known file extensions.
	 */
	void addResolvedFileExtension(String extension, MediaType mediaType) {
		if (extension.indexOf('.') != -1) {
			return;
		}
		String lowerCaseExtension = extension.toLowerCase(Locale.ROOT);
		if (this.mediaTypes.containsKey(lowerCaseExtension)) {
			return;
		}
		this.resolvedFileExtensions.computeIfAbsent(mediaType, key -> new CopyOnWriteArrayList<>())
				.addIfAbsent(lowerCaseExtension);
	}

	private void addFileExtension(MediaType mediaType, String extension) {
		this.fileExtensions.computeIfAbsent(mediaType, key -> new CopyOnWriteArrayList<>())
				.add(extension);
	}


	@Override
	public List<String> resolveFileExtensions(MediaType mediaType) {
		List<String> fileExtensions = this.fileExtensions.get(mediaType);
		List<String> resolved = this.resolvedFileExtensions.get(mediaType);
		if (resolved == null || resolved.isEmpty()) {
			return (fileExtensions != null ? fileExtensions : Collections.emptyList());
		}
		if (fileExtensions == null || fileExtensions.isEmpty()) {
			return Collections.unmodifiableList(resolved);
		}
		List<String> result = new ArrayList<>(fileExtensions);
		for (String extension : resolved) {
			if (!result.contains(extension)) {
				result.add(extension);
			}
		}
		return result;
	}

	@Override
	public List<String> getAllFileExtensions() {
		return Collections.unmodifiableList(this.allFileExtensions);
	}

	/**
	 * Use this method for a reverse lookup from extension to MediaType.
	 * @return a MediaType for the extension, or {@code null} if none found
	 */
	@Nullable
	protected MediaType lookupMediaType(String extension) {
		return this.mediaTypes.get(extension.toLowerCase(Locale.ROOT));
	}

}
