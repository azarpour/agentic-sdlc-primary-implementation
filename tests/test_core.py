import pytest
from orchestrator.core import apply_patch,digest, mock, Analysis

def test_path_escape(tmp_path):
    with pytest.raises(ValueError): apply_patch(tmp_path,{'files':{'../escape.java':'x'}},'src/main/java')
def test_unsafe(tmp_path):
    with pytest.raises(ValueError): apply_patch(tmp_path,{'files':{'src/main/java/X.java':'new ProcessBuilder()'}},'src/main/java')
def test_digest(): assert digest({'a':1,'b':2})==digest({'b':2,'a':1})
def test_ambiguity(): assert mock('requirements',{'scenario':'ambiguous','requirement':'enterprise'},Analysis).ambiguities
