package squire.tools.refrepo

/** Typed failure hierarchy for ReferenceRepo operations. */
enum RefRepoError derives CanEqual:
    /** The given id is not in the manifest. */
    case RepoNotFound(id: String)

    /** The given id already exists in the manifest. */
    case AlreadyExists(id: String)

    /** The manifest YAML could not be parsed. */
    case ManifestParse(detail: String)

    /** A git command exited with a non-zero code. */
    case GitFailed(args: List[String], exit: Int, stderr: String)

    /** The project root is not inside a git repository (used when required). */
    case NotAGitRepo
